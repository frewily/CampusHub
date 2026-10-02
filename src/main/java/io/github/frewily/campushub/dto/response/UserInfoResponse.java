package io.github.frewily.campushub.dto.response;

import lombok.Data;

import java.time.LocalDate;

/** Legacy profile response fields, independent of the persistence schema. */
@Data
public class UserInfoResponse {
    private Long userId;
    private String city;
    private String introduce;
    private Integer fans;
    private Integer followee;
    private Boolean gender;
    private LocalDate birthday;
    private Integer credits;
    private Boolean level;
}
