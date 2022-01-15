package com.bellszhu.elasticsearch.plugin.synonym.analysis;


import com.bellszhu.elasticsearch.plugin.synonym.ext.SynonymDb;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.elasticsearch.common.logging.DeprecationLogger;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.analysis.AbstractTokenFilterFactory;
import org.elasticsearch.index.analysis.CharFilterFactory;
import org.elasticsearch.index.analysis.CustomAnalyzer;
import org.elasticsearch.index.analysis.TokenFilterFactory;
import org.elasticsearch.index.analysis.TokenizerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author bellszhu
 */
public class DynamicSynonymTokenFilterFactory extends
        AbstractTokenFilterFactory {

    private static final DeprecationLogger DEPRECATION_LOGGER
            = new DeprecationLogger(LogManager.getLogger(DynamicSynonymTokenFilterFactory.class));
    private static Logger logger = LogManager.getLogger("dynamic-synonym");

    /**
     * Static id generator
     */
    private static final AtomicInteger id = new AtomicInteger(1);
    private static ScheduledExecutorService pool = Executors.newScheduledThreadPool(1, r -> {
        Thread thread = new Thread(r);
        thread.setName("monitor-synonym-Thread-" + id.getAndAdd(1));
        return thread;
    });
    private volatile ScheduledFuture<?> scheduledFuture;
    private final static ConcurrentHashMap<String,CopyOnWriteArrayList<ScheduledFuture>> scheduledFutures = new ConcurrentHashMap(){};

    private final String location;
    public String url;
    public String dbUser;
    public String dbPass;
    public String dbTable;
    public String type;

    private final boolean expand;
    private final boolean lenient;
    private final String format;
    private final int interval;
    protected SynonymMap synonymMap;
    protected Map<AbsSynonymFilter, Integer> dynamicSynonymFilters = new WeakHashMap<>();
    protected final Environment environment;

    public DynamicSynonymTokenFilterFactory(
            IndexSettings indexSettings,
            Environment env,
            String name,
            Settings settings
    ) throws IOException {
        super(indexSettings, name, settings);

        this.location = settings.get("synonyms_path");
        this.url = settings.get("db_url");
        this.dbTable = settings.get("db_table");
        this.dbUser = settings.get("db_user");
        this.dbPass = settings.get("db_pass");
        this.type = settings.get("dic_type");

        if (this.location == null && this.url == null) {
            throw new IllegalArgumentException(
                    "dynamic synonym requires `synonyms_path || db_url` to be configured");
        }
        if (settings.get("ignore_case") != null) {
            DEPRECATION_LOGGER.deprecated(
                "The ignore_case option on the synonym_graph filter is deprecated. " +
                    "Instead, insert a lowercase filter in the filter chain before the synonym_graph filter.");
        }

        this.interval = settings.getAsInt("interval", 60);
        this.expand = settings.getAsBoolean("expand", true);
        this.lenient = settings.getAsBoolean("lenient", false);
        this.format = settings.get("format", "");
        boolean updateable = settings.getAsBoolean("updateable", false);
        this.environment = env;
    }



    @Override
    public TokenStream create(TokenStream tokenStream) {
        throw new IllegalStateException(
                "Call getChainAwareTokenFilterFactory to specialize this factory for an analysis chain first");
    }

    public static void closeIndDynamicSynonym(String indexName) {
        CopyOnWriteArrayList<ScheduledFuture> futures = scheduledFutures.remove(indexName);
        if (futures != null) {
            for (ScheduledFuture sf : futures) {
                sf.cancel(true);
            }
        }
        logger.info("closeDynamicSynonym！ indexName:{} scheduledFutures.size:{} ",
                indexName, scheduledFutures.size());
    }

    public TokenFilterFactory getChainAwareTokenFilterFactory(
            TokenizerFactory tokenizer,
            List<CharFilterFactory> charFilters,
            List<TokenFilterFactory> previousTokenFilters,
            Function<String, TokenFilterFactory> allFilters
    ) {
        final String name = name();
        final Analyzer analyzer = buildSynonymAnalyzer(name,tokenizer, charFilters, previousTokenFilters, allFilters);
        synonymMap = buildSynonyms(analyzer);
        return new TokenFilterFactory() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public TokenStream create(TokenStream tokenStream) {
                // fst is null means no synonyms
                if (synonymMap.fst == null) {
                    return tokenStream;
                }
                DynamicSynonymFilter dynamicSynonymFilter = new DynamicSynonymFilter(tokenStream, synonymMap, false);
                dynamicSynonymFilters.put(dynamicSynonymFilter, 1);

                return dynamicSynonymFilter;
            }

            @Override
            public TokenFilterFactory getSynonymFilter() {
                // In order to allow chained synonym filters, we return IDENTITY here to
                // ensure that synonyms don't get applied to the synonym map itself,
                // which doesn't support stacked input tokens
                return IDENTITY_FILTER;
            }


        };
    }

    Analyzer buildSynonymAnalyzer(String tokenizerName,
            TokenizerFactory tokenizer,
            List<CharFilterFactory> charFilters,
            List<TokenFilterFactory> tokenFilters,
            Function<String, TokenFilterFactory> allFilters
    ) {
        return new CustomAnalyzer(
                tokenizerName,tokenizer,
                charFilters.toArray(new CharFilterFactory[0]),
                tokenFilters.stream().map(TokenFilterFactory::getSynonymFilter).toArray(TokenFilterFactory[]::new)
        );
    }

    SynonymMap buildSynonyms(Analyzer analyzer) {
        try {
            return getSynonymFile(analyzer).reloadSynonymMap();
        } catch (Exception e) {
            logger.error("failed to build synonyms", e);
            throw new IllegalArgumentException("failed to build synonyms", e);
        }
    }

    SynonymFile getSynonymFile(Analyzer analyzer) {
        try {
            SynonymFile synonymFile;
            if (location == null) {
                synonymFile = new SynonymDb(
                  environment, analyzer, expand, lenient, format, url, dbUser, dbPass, dbTable, type
                );
            } else if (location.startsWith("http://") || location.startsWith("https://")) {
                synonymFile = new RemoteSynonymFile(
                        environment, analyzer, expand, lenient,  format, location);
            } else {
                synonymFile = new LocalSynonymFile(
                        environment, analyzer, expand, lenient, format, location);
            }
            if (scheduledFuture == null) {
                scheduledFuture = pool.scheduleAtFixedRate(new Monitor(synonymFile),
                                interval, interval, TimeUnit.SECONDS);
                scheduledFutures.put(indexSettings.getIndex().getName(),new CopyOnWriteArrayList<ScheduledFuture>(Stream.of(scheduledFuture).collect(Collectors.toList())));
            }
            return synonymFile;
        } catch (Exception e) {
            logger.error("failed to get synonyms: " + location, e);
            throw new IllegalArgumentException("failed to get synonyms : " + location, e);
        }
    }

    public class Monitor implements Runnable {

        private SynonymFile synonymFile;

        Monitor(SynonymFile synonymFile) {
            this.synonymFile = synonymFile;
        }

        @Override
        public void run() {
            if (synonymFile.isNeedReloadSynonymMap()) {
                synonymMap = synonymFile.reloadSynonymMap();
                for (AbsSynonymFilter dynamicSynonymFilter : dynamicSynonymFilters.keySet()) {
                    dynamicSynonymFilter.update(synonymMap);
                    logger.info("success reload synonym");
                }
            }
        }
    }

}
