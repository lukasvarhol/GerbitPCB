package org.gerbitpcb.supplier.murata.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

public class AudienceValidator implements OAuth2TokenValidator<Jwt> {

    private final List<String> requiredAudiences;

    public AudienceValidator(List<String> requiredAudiences) {
        this.requiredAudiences = requiredAudiences;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        List<String> tokenAudiences = jwt.getAudience();
        if (tokenAudiences != null && tokenAudiences.stream().anyMatch(requiredAudiences::contains)) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "Required audience not present", null));
    }
}

