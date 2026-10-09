package io.github.frewily.campushub.service;

import io.github.frewily.campushub.dto.ShopSearchCriteria;
import io.github.frewily.campushub.dto.request.ShopSearchRequest;
import io.github.frewily.campushub.dto.response.*;
import io.github.frewily.campushub.exception.*;
import io.github.frewily.campushub.mapper.ShopSearchMapper;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import javax.validation.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShopSearchServiceTest {
    private static ValidatorFactory factory;
    private ShopSearchMapper mapper;
    private ShopSearchService search;
    @BeforeAll static void validation() { factory=Validation.buildDefaultValidatorFactory(); }
    @AfterAll static void close() { factory.close(); }
    @BeforeEach void setup() {
        mapper=mock(ShopSearchMapper.class); search=new ShopSearchService(mapper,factory.getValidator());
    }
    @Test void normalizedLiteralKeywordIsBoundAndPaginationIsBounded() {
        ShopSearchRequest request=new ShopSearchRequest(); request.setKeyword("  %_!\\'  ");
        request.setPage(500); request.setSize(50);
        search.search(request);
        ArgumentCaptor<ShopSearchCriteria> captured=ArgumentCaptor.forClass(ShopSearchCriteria.class);
        verify(mapper).count(captured.capture());
        assertEquals("%!%!_!!\\'%",captured.getValue().getLikeKeyword());
        assertEquals(24950,captured.getValue().getOffset());
        assertEquals(50,captured.getValue().getSize());
        assertEquals("  %_!\\'  ",request.getKeyword()); // Request is not mutated into SQL syntax.
    }
    @Test void emptyResultRetainsMetadataAndDoesNotFetchASecondTime() {
        ShopSearchRequest request=new ShopSearchRequest(); request.setKeyword("   ");
        ShopSearchPage result=search.search(request);
        assertEquals(0,result.getTotal()); assertEquals(1,result.getPage()); assertFalse(result.isHasNext());
        assertTrue(result.getItems().isEmpty()); verify(mapper,never()).search(any());
        ArgumentCaptor<ShopSearchCriteria> captured=ArgumentCaptor.forClass(ShopSearchCriteria.class);
        verify(mapper).count(captured.capture()); assertNull(captured.getValue().getLikeKeyword());
    }
    @Test void exactPageBoundaryAndBeyondLastPageHaveNoNextPage() {
        when(mapper.count(any())).thenReturn(20L);
        when(mapper.search(any())).thenReturn(Collections.emptyList());
        ShopSearchRequest request=new ShopSearchRequest();
        assertTrue(search.search(request).isHasNext());
        request.setPage(2); assertFalse(search.search(request).isHasNext());
        request.setPage(3); assertFalse(search.search(request).isHasNext());
    }
    @Test void internalCallersCannotBypassParameterValidation() {
        List<ShopSearchRequest> bad=new ArrayList<>();
        ShopSearchRequest page=new ShopSearchRequest(); page.setPage(501); bad.add(page);
        ShopSearchRequest size=new ShopSearchRequest(); size.setSize(Integer.MAX_VALUE); bad.add(size);
        ShopSearchRequest keyword=new ShopSearchRequest(); keyword.setKeyword(String.join("",Collections.nCopies(81,"x"))); bad.add(keyword);
        ShopSearchRequest sort=new ShopSearchRequest(); sort.setSort("id; DROP TABLE tb_shop"); bad.add(sort);
        ShopSearchRequest price=new ShopSearchRequest(); price.setMinPrice(100L); price.setMaxPrice(0L); bad.add(price);
        ShopSearchRequest score=new ShopSearchRequest(); score.setMinScore(51); bad.add(score);
        ShopSearchRequest coords=new ShopSearchRequest(); coords.setX(1D); bad.add(coords);
        ShopSearchRequest infinity=new ShopSearchRequest(); infinity.setX(Double.NaN); infinity.setY(0D); bad.add(infinity);
        ShopSearchRequest radius=new ShopSearchRequest(); radius.setRadiusMeters(1); bad.add(radius);
        ShopSearchRequest distance=new ShopSearchRequest(); distance.setSort("distance"); bad.add(distance);
        for(ShopSearchRequest request:bad) assertEquals(ErrorCode.VALIDATION_FAILED,
                assertThrows(BusinessException.class,()->search.search(request)).getErrorCode());
        assertThrows(BusinessException.class,()->search.search(null)); verifyNoInteractions(mapper);
    }
    @Test void coordinateBoundariesIncludingDatelineAndPolesAreValid() {
        ShopSearchRequest request=new ShopSearchRequest(); request.setX(-180D); request.setY(90D); request.setSort("distance");
        assertEquals(0,search.search(request).getTotal());
        request.setX(180D); request.setY(-90D); request.setRadiusMeters(50000);
        assertEquals(0,search.search(request).getTotal());
    }
    @Test void unavailableDatabaseIsNotAnInventedEmptyResult() {
        when(mapper.count(any())).thenThrow(new DataAccessResourceFailureException("private database details"));
        BusinessException error=assertThrows(BusinessException.class,()->search.search(new ShopSearchRequest()));
        assertEquals(ErrorCode.SHOP_STATE_UNAVAILABLE,error.getErrorCode());
        assertFalse(error.getMessage().contains("private")); verify(mapper,never()).search(any());
    }
    @Test void pageFailureAfterCountIsAlsoTypedUnavailable() {
        when(mapper.count(any())).thenReturn(1L);
        when(mapper.search(any())).thenThrow(new DataAccessResourceFailureException("private SQL"));
        assertEquals(ErrorCode.SHOP_STATE_UNAVAILABLE,assertThrows(BusinessException.class,
                ()->search.search(new ShopSearchRequest())).getErrorCode());
    }
}
