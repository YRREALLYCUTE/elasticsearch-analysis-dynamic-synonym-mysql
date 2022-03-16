package com.bellszhu.elasticsearch.plugin.synonym.ext;

import com.bellszhu.elasticsearch.plugin.synonym.analysis.SynonymFile;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.analysis.ESSolrSynonymParser;
import org.elasticsearch.index.analysis.ESWordnetSynonymParser;

/**
 * 通过mysql加载词表
 * @author NKU_DBIS, sunjingqi
 * @date 2022/1/15
 */
public class SynonymDb implements SynonymFile {

    private static final Logger logger = LogManager.getLogger("dynamic-synonym");

    private String format;

    private boolean expand;

    private boolean lenient;

    private Analyzer analyzer;

    private Environment env;

    // 从配置中加载的信息
    public String url;
    public String dbUser;
    public String dbPass;
    public String dbTable;
    public String type;
    public String style;

    private Connection connection;
    public Map<String, Date> lastImportTime = new HashMap<>();

    static {
        try {
            Class.forName("com.mysql.jdbc.Driver");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    public SynonymDb () {
        this.url = "jdbc:mysql://localhost:3306/es_data";
        this.dbUser = "root";
        this.dbPass = "root";
        this.dbTable = "t_es_synonym_dic";
    }

    public SynonymDb(Environment env, Analyzer analyzer, boolean expand, boolean lenient,
              String format, String url, String dbUser, String dbPwd, String dbTable,
              String type, String style) {
        this.env = env;
        this.analyzer = analyzer;
        this.expand = expand;
        this.lenient = lenient;
        this.format = format;

        this.url = url;
        this.dbUser = dbUser;
        this.dbPass = dbPwd;
        this.dbTable = dbTable;
        if (type == null || "".equals(type)) {
            this.type = "all";
        } else {
            this.type = type;
        }

        if(style == null || "".equals(style)) {
            this.style = "inline";
        } else {
            this.style = style;
        }
    }

    @Override
    public SynonymMap reloadSynonymMap() {
        // 重载synonymMap
        try {
            logger.info("start reload database synonym from {}-{}.", url, dbTable);
            Reader rulesReader = getReader();
            SynonymMap.Builder parser = getSynonymParser(
                    rulesReader, format, expand, lenient, analyzer);
            return parser.build();
        } catch (Exception e) {
            logger.info("start reload database synonym from {}-{}.", url, dbTable);
            throw new IllegalArgumentException(
                    "could not reload local synonyms file to build synonyms", e);
        }
    }

    @Override
    public boolean isNeedReloadSynonymMap() {
        // 检测 lastModifyTime 是否比对应记录的时间小
        String key = style + ":" + type;
        if (lastImportTime.containsKey(key)) {
            Date last = getLastModifyTime(key);
            if (last.getTime() > lastImportTime.get(key).getTime()) {
                lastImportTime.put(key, last);
                return true;
            } else {
                return false;
            }
        } else {
            lastImportTime.put(key, new Date(0));
            return false;
        }
    }

    @Override
    public Reader getReader() {
        List<String> data;
        // 从数据库中查询出关键词，并使用 ‘,’ 拼接
        if ("multi_line".equals(style)) {
            data = getWordsMultiline(type);
        } else {
            data = getWordsInline(type);
        }
        StringBuilder sb = new StringBuilder();
        for (String s : data) {
            sb.append(s).append(System.getProperty("line.separator"));
        }
        return new StringReader(sb.toString());
    }

    private static SynonymMap.Builder getSynonymParser(
            Reader rulesReader, String format, boolean expand, boolean lenient, Analyzer analyzer
    ) throws IOException, ParseException {
        SynonymMap.Builder parser;
        if ("wordnet".equalsIgnoreCase(format)) {
            parser = new ESWordnetSynonymParser(true, expand, lenient, analyzer);
            ((ESWordnetSynonymParser) parser).parse(rulesReader);
        } else {
            parser = new ESSolrSynonymParser(true, expand, lenient, analyzer);
            ((ESSolrSynonymParser) parser).parse(rulesReader);
        }
        return parser;
    }

    /**
     * 获取数据库连接
     * @return connection
     */
    private Connection getConnection() {
        try {
            // logger.info("url: {}; user: {}; pass: {}", url, dbUser, dbPass);
            connection = DriverManager.getConnection(url, dbUser, dbPass);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return connection;
    }

    /**
     * 获取同义词表 方式1
     * @param type 同义词的类型
     * @return list
     */
    public List<String> getWordsInline(String type) {
        connection = getConnection();
        List<String> data = new ArrayList<>();
        PreparedStatement ps = null;
        ResultSet rs = null;

        try {
            StringBuilder sql = new StringBuilder("select * from " + dbTable + " where status = 0");
            if (!"all".equals(type)) {
                sql.append(" and type = '").append(type).append("'");
            }
            logger.log(Level.INFO, "sql==={}", sql.toString());
            ps = connection.prepareStatement(sql.toString());
            rs = ps.executeQuery();
            while (rs.next()) {
                String words = rs.getString("words");
                if (words != null && !"".equals(words)) {
                    data.add(words);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (ps != null) {
                    ps.close();
                }
                if (rs != null) {
                    rs.close();
                }
                if (connection != null) {
                    connection.close();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return data;
    }

    /**
     * 获取同义词表 方式2
     */
    public List<String> getWordsMultiline(String type) {
        connection = getConnection();
        List<String> data = new ArrayList<>();
        HashMap<String, List<String>> map = new HashMap<>();
        PreparedStatement ps = null;
        ResultSet rs = null;

        try {
            StringBuilder sql = new StringBuilder("select * from " + dbTable + " where in_use = 1 and status = 0");
            if (!"all".equals(type)) {
                sql.append(" and type = '").append(type).append("'");
            }
            logger.log(Level.INFO, "sql==={}", sql.toString());
            ps = connection.prepareStatement(sql.toString());
            rs = ps.executeQuery();
            while (rs.next()) {
                String keyword = rs.getString("keyword");
                String mainWord = rs.getString("main_word");
                if (keyword != null && !"".equals(keyword)) {
                    if (map.containsKey(mainWord)) {
                        map.get(mainWord).add(keyword);
                    } else {
                        List<String> keywordList = new ArrayList<>();
                        keywordList.add(keyword);
                        map.put(mainWord, keywordList);
                    }
                }
            }
            for (String s : map.keySet()) {
                StringBuilder sb = new StringBuilder();
                for (String word : map.get(s)) {
                    sb.append(",").append(word);
                }
                data.add(sb.toString().replaceFirst(",", ""));
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (ps != null) {
                    ps.close();
                }
                if (rs != null) {
                    rs.close();
                }
                if (connection != null) {
                    connection.close();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return data;
    }

    /**
     * 获取某类型的最后修改时间
     * @param type 同义词类型
     * @return 修改时间
     */
    private Date getLastModifyTime(String type) {
        connection = getConnection();
        PreparedStatement ps = null;
        ResultSet rs = null;
        Date rt = null;

        try {
            StringBuilder sql = new StringBuilder("select max(update_time) as update_time from " + dbTable + " where status = 0");
            if (!"all".equals(type)) {
                sql.append(" and type = '").append(type).append("'");
            }
            logger.log(Level.INFO, "sql==={}", sql.toString());
            ps = connection.prepareStatement(sql.toString());
            rs = ps.executeQuery();
            while (rs.next()) {
                String createTime = rs.getString("update_time");
                if (createTime != null && !"".equals(createTime)) {
                    rt = parseDate(createTime);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (ps != null) {
                    ps.close();
                }
                if (rs != null) {
                    rs.close();
                }
                if (connection != null) {
                    connection.close();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return rt;
    }

    /**
     * @param dateString 时间字符串
     * @return date
     */
    private Date parseDate(String dateString) {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        try {
            return dateFormat.parse(dateString);
        } catch (ParseException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static void main(String[] args) {
        SynonymDb db = new SynonymDb();
        List<String> extWords=db.getWordsInline("test");
        System.out.println(extWords);

        Date max = db.getLastModifyTime("test");
        System.out.println(max);
    }
}
