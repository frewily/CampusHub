package io.github.frewily.campushub.service;

import io.github.frewily.campushub.dto.ShopSearchCriteria;
import io.github.frewily.campushub.dto.request.ShopSearchRequest;
import io.github.frewily.campushub.dto.response.ShopSearchPage;
import io.github.frewily.campushub.exception.*;
import io.github.frewily.campushub.mapper.ShopSearchMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import javax.validation.Validator;
import java.util.Collections;

@Slf4j
@Service
public class ShopSearchService {
    private final ShopSearchMapper mapper;
    private final Validator validator;
    public ShopSearchService(ShopSearchMapper mapper, Validator validator) { this.mapper = mapper; this.validator = validator; }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ShopSearchPage search(ShopSearchRequest request) {
        if (request == null || !validator.validate(request).isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        ShopSearchCriteria criteria = new ShopSearchCriteria(request);
        try {
            long total = mapper.count(criteria);
            return new ShopSearchPage(total == 0 ? Collections.emptyList() : mapper.search(criteria), total,
                    request.getPage(), request.getSize(), request.getSort(),
                    ((long) request.getPage()) * request.getSize() < total);
        } catch (DataAccessException error) {
            // Do not log exception text/stack: drivers may embed SQL, bound values or connection details.
            log.warn("Shop search database access failed, errorClass={}", error.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.SHOP_STATE_UNAVAILABLE);
        }
    }
}
