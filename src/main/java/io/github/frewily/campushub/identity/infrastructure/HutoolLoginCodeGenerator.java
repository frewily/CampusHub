package io.github.frewily.campushub.identity.infrastructure;

import cn.hutool.core.util.RandomUtil;
import io.github.frewily.campushub.identity.application.LoginCodeGenerator;
import org.springframework.stereotype.Component;

@Component
public class HutoolLoginCodeGenerator implements LoginCodeGenerator {
    @Override
    public String generate() { return RandomUtil.randomNumbers(6); }
}
