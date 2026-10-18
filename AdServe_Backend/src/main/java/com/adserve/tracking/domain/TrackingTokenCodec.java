package com.adserve.tracking.domain;

public interface TrackingTokenCodec {
    String sign(TrackingToken token);

    /** @throws InvalidTrackingToken on malformed input, unknown key id, or signature mismatch */
    TrackingToken verify(String signedToken);
}
