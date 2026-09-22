package io.github.frewily.campushub.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

import static io.github.frewily.campushub.utils.RegexPatterns.PASSWORD_REGEX;
import static io.github.frewily.campushub.utils.RegexPatterns.PHONE_REGEX;
import static io.github.frewily.campushub.utils.RegexPatterns.VERIFY_CODE_REGEX;

@Data
public class LoginFormDTO {

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = PHONE_REGEX, message = "手机号格式错误")
    private String phone;

    @NotBlank(message = "验证码不能为空")
    @Pattern(regexp = VERIFY_CODE_REGEX, message = "验证码格式错误")
    private String code;

    @Pattern(regexp = PASSWORD_REGEX, message = "密码格式错误")
    private String password;
}
