package com.bellszhu.elasticsearch.plugin;

import com.bellszhu.elasticsearch.plugin.synonym.analysis.DynamicSynonymTokenFilterFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.shard.IndexEventListener;
import org.elasticsearch.indices.cluster.IndicesClusterStateService;

/**
 * @author lxc
 * @version 1.0
 * @date 2021/4/1
 */
public class DynamicSynonymIndexEventListener implements IndexEventListener {

    private static Logger logger = LogManager.getLogger("dynamic-synonym");

    @Override
    public void beforeIndexRemoved(IndexService indexService, IndicesClusterStateService.AllocatedIndices.IndexRemovalReason reason) {
        logger.info("beforeIndexRemoved ! indexName:{}",indexService.index().getName());
        DynamicSynonymTokenFilterFactory.closeIndDynamicSynonym(indexService.index().getName());
    }
}
