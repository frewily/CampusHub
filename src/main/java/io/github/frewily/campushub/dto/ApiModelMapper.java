package io.github.frewily.campushub.dto;

import io.github.frewily.campushub.dto.request.*;
import io.github.frewily.campushub.dto.response.UserInfoResponse;
import io.github.frewily.campushub.entity.Blog;
import io.github.frewily.campushub.entity.Shop;
import io.github.frewily.campushub.entity.UserInfo;
import io.github.frewily.campushub.entity.Voucher;

/** Explicit mappings keep newly added entity fields outside the API write surface. */
public final class ApiModelMapper {
    private ApiModelMapper() {
    }

    public static Shop toShop(ShopCreateRequest request) {
        return new Shop()
                .setName(request.getName()).setTypeId(request.getTypeId())
                .setMerchantId(request.getMerchantId()).setImages(request.getImages())
                .setArea(request.getArea()).setAddress(request.getAddress())
                .setX(request.getX()).setY(request.getY())
                .setAvgPrice(request.getAvgPrice()).setOpenHours(request.getOpenHours())
                .setSold(0).setComments(0).setScore(0);
    }

    public static Shop toShop(ShopUpdateRequest request) {
        return new Shop()
                .setId(request.getId()).setName(request.getName()).setTypeId(request.getTypeId())
                .setImages(request.getImages()).setArea(request.getArea()).setAddress(request.getAddress())
                .setX(request.getX()).setY(request.getY())
                .setAvgPrice(request.getAvgPrice()).setOpenHours(request.getOpenHours());
    }

    public static Blog toBlog(BlogCreateRequest request) {
        return new Blog().setShopId(request.getShopId()).setTitle(request.getTitle())
                .setImages(request.getImages()).setContent(request.getContent())
                .setLiked(0).setComments(0);
    }

    public static Voucher toVoucher(VoucherCreateRequest request) {
        return new Voucher().setShopId(request.getShopId()).setTitle(request.getTitle())
                .setSubTitle(request.getSubTitle()).setRules(request.getRules())
                .setPayValue(request.getPayValue()).setActualValue(request.getActualValue())
                .setType(0).setStatus(1);
    }

    public static Voucher toFlashSale(FlashSaleCreateRequest request) {
        return toVoucher(request).setType(1).setStock(request.getStock())
                .setBeginTime(request.getBeginTime()).setEndTime(request.getEndTime());
    }

    public static UserInfoResponse toUserInfoResponse(UserInfo info) {
        UserInfoResponse response = new UserInfoResponse();
        response.setUserId(info.getUserId());
        response.setCity(info.getCity());
        response.setIntroduce(info.getIntroduce());
        response.setFans(info.getFans());
        response.setFollowee(info.getFollowee());
        response.setGender(info.getGender());
        response.setBirthday(info.getBirthday());
        response.setCredits(info.getCredits());
        response.setLevel(info.getLevel());
        return response;
    }
}
