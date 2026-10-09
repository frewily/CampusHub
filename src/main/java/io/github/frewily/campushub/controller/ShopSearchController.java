package io.github.frewily.campushub.controller;

import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.exception.ErrorCode;
import io.github.frewily.campushub.dto.request.ShopSearchRequest;
import io.github.frewily.campushub.service.ShopSearchService;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import javax.validation.Valid;

@RestController
@RequestMapping("/shop/search")
public class ShopSearchController {
    private final ShopSearchService search;
    public ShopSearchController(ShopSearchService search) { this.search = search; }

    @InitBinder("shopSearchRequest")
    void searchFields(WebDataBinder binder) {
        binder.setAllowedFields("keyword", "typeId", "minPrice", "maxPrice", "minScore", "x", "y", "radiusMeters", "sort", "page", "size");
    }
    @GetMapping
    public Result search(@Valid @ModelAttribute("shopSearchRequest") ShopSearchRequest request) {
        return Result.ok(search.search(request));
    }

    // Transaction begin/commit occur outside the service method's mapper exception boundary.
    @ExceptionHandler({TransactionException.class, DataAccessException.class})
    public ResponseEntity<Result> unavailable(RuntimeException error) {
        return ResponseEntity.status(ErrorCode.SHOP_STATE_UNAVAILABLE.getHttpStatus())
                .body(Result.fail(ErrorCode.SHOP_STATE_UNAVAILABLE));
    }
}
