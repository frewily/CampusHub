package io.github.frewily.campushub.service;

import io.github.frewily.campushub.dto.ApiModelMapper;
import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.dto.request.BlogCreateRequest;
import io.github.frewily.campushub.entity.Blog;
import io.github.frewily.campushub.entity.BlogPublicationRequest;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import io.github.frewily.campushub.mapper.BlogPublicationRequestMapper;
import io.github.frewily.campushub.utils.UserHolder;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.validation.Validator;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

/** Optional request-key receipts share the post/outbox transaction; no cached HTTP response or credentials. */
@Service
public class BlogPublicationService {
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private final IBlogService blogs;
    private final BlogPublicationRequestMapper requests;
    private final Validator validator;

    public BlogPublicationService(IBlogService blogs, BlogPublicationRequestMapper requests, Validator validator) {
        this.blogs = blogs;
        this.requests = requests;
        this.validator = validator;
    }

    public static void validateKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "发布请求编号格式无效");
        }
    }

    @Transactional
    public Result publish(BlogCreateRequest request, String key) {
        UserDTO author = UserHolder.getUser();
        if (author == null || author.getId() == null || author.getId() <= 0) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_FAILED);
        }
        validateKey(key);
        if (request == null || !validator.validate(request).isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        Blog blog = ApiModelMapper.toBlog(request);
        String hash = fingerprint(blog);
        BlogFeedDeliveryService.requireWritableTransaction();
        try {
            try {
                if (requests.reserve(author.getId(), key, hash) != 1) {
                    throw new BusinessException(ErrorCode.OPERATION_FAILED);
                }
            } catch (DuplicateKeyException alreadyReserved) {
                // InnoDB rolls back the conflicting statement, not this surrounding MyBatis transaction.
                BlogPublicationRequest receipt = requests.findForReplay(author.getId(), key);
                if (receipt == null) throw new BusinessException(ErrorCode.INTERNAL_ERROR);
                if (!hash.equals(receipt.getRequestHash())) throw new BusinessException(ErrorCode.CONFLICT);
                if (receipt.getBlogId() == null || receipt.getBlogId() <= 0) {
                    throw new BusinessException(ErrorCode.INTERNAL_ERROR);
                }
                return Result.ok(receipt.getBlogId());
            }

            Result created = blogs.saveBlog(blog);
            if (created == null || !Boolean.TRUE.equals(created.getSuccess())
                    || !(created.getData() instanceof Long) || (Long) created.getData() <= 0) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR);
            }
            Long id = (Long) created.getData();
            if (requests.complete(author.getId(), key, hash, id) != 1) {
                throw new BusinessException(ErrorCode.OPERATION_FAILED);
            }
            return Result.ok(id);
        } catch (DataAccessException unavailable) {
            // Never return/store/log the request key, body, SQL or exception message.
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    /** Versioned, length-prefixed Java UTF-16 code units: exact even for unusual Unicode input. */
    static String fingerprint(Blog blog) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("CampusHub:POST/blog:v1".getBytes(StandardCharsets.US_ASCII));
            digest.update(ByteBuffer.allocate(8).putLong(blog.getShopId() == null ? -1L : blog.getShopId()).array());
            field(digest, blog.getTitle());
            field(digest, blog.getImages());
            field(digest, blog.getContent());
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest.digest()) {
                hex.append(Character.forDigit((b >>> 4) & 15, 16));
                hex.append(Character.forDigit(b & 15, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Required request fingerprint algorithm unavailable");
        }
    }

    private static void field(MessageDigest digest, String text) {
        digest.update(ByteBuffer.allocate(4).putInt(text == null ? -1 : text.length()).array());
        if (text != null) {
            for (int i = 0; i < text.length(); i++) {
                char unit = text.charAt(i);
                digest.update((byte) (unit >>> 8));
                digest.update((byte) unit);
            }
        }
    }
}
