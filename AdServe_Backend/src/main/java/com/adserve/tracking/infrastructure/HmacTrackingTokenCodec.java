package com.adserve.tracking.infrastructure;

import com.adserve.shared.AdId;
import com.adserve.shared.CampaignId;
import com.adserve.shared.ReservationId;
import com.adserve.shared.ValidationException;
import com.adserve.tracking.domain.InvalidTrackingToken;
import com.adserve.tracking.domain.TrackingToken;
import com.adserve.tracking.domain.TrackingTokenCodec;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * Token format: {kid}.{base64url(payload)}.{base64url(HMAC-SHA256(kid + "." + payloadB64))}
 * payload: reservationId|campaignId|adId|publisherId|expiresAtEpochMillis
 * Multiple keys are accepted for verification so the signing key can be rotated.
 */
public final class HmacTrackingTokenCodec implements TrackingTokenCodec {
    private static final String ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final String signingKeyId;
    private final Map<String, byte[]> keys;

    public HmacTrackingTokenCodec(String signingKeyId, Map<String, byte[]> keys) {
        if (!keys.containsKey(signingKeyId)) {
            throw new IllegalArgumentException("signing key id not present in key set");
        }
        keys.values().forEach(k -> {
            if (k.length < 32) {
                throw new IllegalArgumentException("HMAC keys must be at least 32 bytes");
            }
        });
        this.signingKeyId = signingKeyId;
        this.keys = Map.copyOf(keys);
    }

    @Override
    public String sign(TrackingToken token) {
        if (token.publisherId().indexOf('|') >= 0) {
            throw new ValidationException("publisherId contains a reserved character");
        }
        String payload = String.join("|", token.reservationId().toString(), token.campaignId().toString(),
                token.adId().toString(), token.publisherId(), Long.toString(token.expiresAt().toEpochMilli()));
        String payloadB64 = ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String signedPart = signingKeyId + "." + payloadB64;
        return signedPart + "." + ENCODER.encodeToString(hmac(keys.get(signingKeyId), signedPart));
    }

    @Override
    public TrackingToken verify(String signedToken) {
        if (signedToken == null) {
            throw new InvalidTrackingToken("missing token");
        }
        String[] parts = signedToken.split("\\.", -1);
        if (parts.length != 3) {
            throw new InvalidTrackingToken("malformed token");
        }
        byte[] key = keys.get(parts[0]);
        if (key == null) {
            throw new InvalidTrackingToken("unknown key id");
        }
        byte[] presented;
        try {
            presented = DECODER.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new InvalidTrackingToken("malformed signature");
        }
        byte[] expected = hmac(key, parts[0] + "." + parts[1]);
        if (!MessageDigest.isEqual(expected, presented)) {
            throw new InvalidTrackingToken("signature mismatch");
        }
        try {
            String payload = new String(DECODER.decode(parts[1]), StandardCharsets.UTF_8);
            String[] f = payload.split("\\|", -1);
            if (f.length != 5) {
                throw new InvalidTrackingToken("malformed payload");
            }
            return new TrackingToken(ReservationId.of(f[0]), CampaignId.of(f[1]), AdId.of(f[2]), f[3],
                    Instant.ofEpochMilli(Long.parseLong(f[4])));
        } catch (IllegalArgumentException | ValidationException e) {
            throw new InvalidTrackingToken("malformed payload");
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }
}
