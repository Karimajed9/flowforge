package com.karimmajed.flowforge.dto;

import java.util.List;

/**
 * What the browser may see about the signed-in caller.
 * Access, refresh, and ID tokens are not part of this response.
 */
public record AuthProfile(
        String subject,
        String issuer,
        String username,
        List<String> permissions
) {
}
