package com.adserve.tracking;

import com.adserve.shared.AdId;
import com.adserve.shared.CampaignId;
import com.adserve.shared.ReservationId;
import com.adserve.tracking.domain.InvalidTrackingToken;
import com.adserve.tracking.domain.TrackingToken;
import com.adserve.tracking.infrastructure.HmacTrackingTokenCodec;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HmacTrackingTokenCodecTest {
    static byte[] key(int fill) {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) fill);
        return k;
    }

    final TrackingToken token = new TrackingToken(ReservationId.newId(), CampaignId.newId(), AdId.newId(), "pub-101",
            Instant.parse("2026-11-02T10:10:00Z"));
    final HmacTrackingTokenCodec codec = new HmacTrackingTokenCodec("k1", Map.of("k1", key(1)));

    @Test
    void signedTokenRoundTrips() {
        assertEquals(token, codec.verify(codec.sign(token)));
    }

    // TRK-01
    @Test
    void tamperedPayloadIsRejected() {
        String[] parts = codec.sign(token).split("\\.");
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                (token.reservationId() + "|" + CampaignId.newId() + "|" + token.adId() + "|pub-101|"
                        + token.expiresAt().toEpochMilli()).getBytes());
        String forged = parts[0] + "." + forgedPayload + "." + parts[2];
        assertThrows(InvalidTrackingToken.class, () -> codec.verify(forged));
    }

    @Test
    void tamperedSignatureIsRejected() {
        String signed = codec.sign(token);
        String forged = signed.substring(0, signed.length() - 2) + (signed.endsWith("AA") ? "BB" : "AA");
        assertThrows(InvalidTrackingToken.class, () -> codec.verify(forged));
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        HmacTrackingTokenCodec attacker = new HmacTrackingTokenCodec("k1", Map.of("k1", key(9)));
        assertThrows(InvalidTrackingToken.class, () -> codec.verify(attacker.sign(token)));
    }

    @Test
    void malformedInputIsRejected() {
        assertThrows(InvalidTrackingToken.class, () -> codec.verify(null));
        assertThrows(InvalidTrackingToken.class, () -> codec.verify(""));
        assertThrows(InvalidTrackingToken.class, () -> codec.verify("a.b"));
        assertThrows(InvalidTrackingToken.class, () -> codec.verify("k1.bm90LXZhbGlk.@@@"));
        assertThrows(InvalidTrackingToken.class, () -> codec.verify("unknown.bm90LXZhbGlk.AAAA"));
    }

    @Test
    void keyRotationKeepsOldTokensValid() {
        String oldToken = codec.sign(token);
        Map<String, byte[]> both = new HashMap<>();
        both.put("k1", key(1));
        both.put("k2", key(2));
        HmacTrackingTokenCodec rotated = new HmacTrackingTokenCodec("k2", both);
        assertEquals(token, rotated.verify(oldToken));
        assertEquals(token, rotated.verify(rotated.sign(token)));
        assertThrows(InvalidTrackingToken.class, () -> codec.verify(rotated.sign(token)));
    }

    @Test
    void shortKeysAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new HmacTrackingTokenCodec("k", Map.of("k", new byte[8])));
    }

    // TRK-05 (identity part)
    @Test
    void eventIdsAreDerivedFromTheReservationSoDuplicatesCollapse() {
        assertEquals(token.reservationId() + ":IMP", token.impressionEventId());
        assertEquals(token.reservationId() + ":CLK", token.clickEventId());
    }
}
