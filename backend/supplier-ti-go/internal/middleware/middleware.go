package middleware

import (
    "net/http"
    "net/url"
    "time"

    "github.com/auth0/go-jwt-middleware/v2"
    "github.com/auth0/go-jwt-middleware/v2/jwks"
    "github.com/auth0/go-jwt-middleware/v2/validator"
    "github.com/gin-gonic/gin"
)

func AuthMiddleware(domain string, audience string) gin.HandlerFunc {
    issuerURL, _ := url.Parse("https://" + domain + "/")
    provider := jwks.NewCachingProvider(issuerURL, 5*time.Minute)

    jwtValidator, _ := validator.New(
        provider.KeyFunc,
        validator.RS256,
        issuerURL.String(),
        []string{audience},
    )

    middleware := jwtmiddleware.New(jwtValidator.ValidateToken)

    return func(c *gin.Context) {
        encounteredError := true
        var handler http.HandlerFunc = func(w http.ResponseWriter, r *http.Request) {
            encounteredError = false
            c.Request = r
            c.Next()
        }
        middleware.CheckJWT(handler).ServeHTTP(c.Writer, c.Request)
        if encounteredError {
            c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "JWT validation failed"})
        }
    }
}
