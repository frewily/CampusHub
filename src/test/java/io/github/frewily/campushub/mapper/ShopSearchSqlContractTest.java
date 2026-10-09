package io.github.frewily.campushub.mapper;

import io.github.frewily.campushub.dto.ShopSearchCriteria;
import io.github.frewily.campushub.dto.request.ShopSearchRequest;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import java.io.InputStream;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class ShopSearchSqlContractTest {
    private Configuration config;
    @BeforeEach void setup() throws Exception {
        config=new Configuration();
        try(InputStream xml=getClass().getResourceAsStream("/mapper/ShopSearchMapper.xml")) {
            new XMLMapperBuilder(xml,config,"mapper/ShopSearchMapper.xml",config.getSqlFragments()).parse();
        }
    }
    private BoundSql sql(String method,ShopSearchRequest request) {
        return config.getMappedStatement(ShopSearchMapper.class.getName()+"."+method)
                .getBoundSql(Collections.singletonMap("q",new ShopSearchCriteria(request)));
    }
    @Test void sortBranchesAreFixedSqlAndUserDataNeverGetsInterpolated() {
        for(String sort:new String[]{"id","price_asc","price_desc","score_desc","distance"}) {
            ShopSearchRequest request=new ShopSearchRequest(); request.setSort(sort);
            request.setKeyword("' OR 1=1 --"); request.setX(118D); request.setY(30D); request.setRadiusMeters(5000);
            String generated=sql("search",request).getSql().replaceAll("\\s+"," ");
            assertFalse(generated.contains("OR 1=1")); assertTrue(generated.contains("s.name LIKE ? ESCAPE '!'"));
            assertTrue(generated.contains("s.id ASC LIMIT ? OFFSET ?"));
            assertFalse(generated.contains("SELECT *")); assertFalse(generated.contains("merchant_id"));
        }
    }
    @Test void countAndPageShareTheSameRadiusAndScalarFilters() {
        ShopSearchRequest request=new ShopSearchRequest(); request.setMinPrice(0L); request.setMaxPrice(100L);
        request.setMinScore(40); request.setTypeId(1L); request.setX(118D); request.setY(30D); request.setRadiusMeters(5000);
        String count=sql("count",request).getSql().replaceAll("\\s+"," ");
        String page=sql("search",request).getSql().replaceAll("\\s+"," ");
        String countWhere=count.substring(count.indexOf("WHERE")).trim();
        String pageWhere=page.substring(page.indexOf("WHERE"),page.indexOf("ORDER BY")).trim();
        assertEquals(countWhere,pageWhere);
    }
    @Test void xmlStatementsActuallyCarryTheFiveSecondQueryTimeout() {
        assertEquals(5,config.getMappedStatement(ShopSearchMapper.class.getName()+".count").getTimeout());
        assertEquals(5,config.getMappedStatement(ShopSearchMapper.class.getName()+".search").getTimeout());
    }
}
