package mag.mizarstack.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class AdminAuthService {

    private static final Pattern SHA256_HEX_PATTERN = Pattern.compile("^[0-9a-fA-F]{64}$");

    private final byte[] expectedHashBytes;

    public AdminAuthService(@Value("${app.admin.password:}") String adminPassword) {
        String normalizedPassword = adminPassword == null ? "" : adminPassword.trim();
        if (normalizedPassword.isBlank()) {
            this.expectedHashBytes = null;
            return;
        }
        this.expectedHashBytes = sha256(normalizedPassword);
    }

    public boolean isConfigured() {
        return expectedHashBytes != null;
    }

    public boolean authorize(String authorizationHeader) {
        if (!isConfigured()) {
            return false;
        }
        String token = extractToken(authorizationHeader);
        if (!SHA256_HEX_PATTERN.matcher(token).matches()) {
            return false;
        }
        try {
            byte[] provided = HexFormat.of().parseHex(token.toLowerCase(Locale.ROOT));
            return MessageDigest.isEqual(expectedHashBytes, provided);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String extractToken(String authorizationHeader) {
        if (authorizationHeader == null) {
            return "";
        }
        String trimmed = authorizationHeader.trim();
        if (trimmed.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            return trimmed.substring("Bearer ".length()).trim();
        }
        return trimmed;
    }

    private static byte[] sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot initialize SHA-256 digest", ex);
        }
    }
}
