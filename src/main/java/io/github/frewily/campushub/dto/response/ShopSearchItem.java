package io.github.frewily.campushub.dto.response;

import lombok.Data;

/** Public read whitelist, with lossless string identifiers and no ownership/internal columns. */
@Data
public class ShopSearchItem {
    private String id;
    private String typeId;
    private String name;
    private String images;
    private String area;
    private String address;
    private Long avgPrice;
    private Integer score;
    private String openHours;
    private Double distanceMeters;
}
