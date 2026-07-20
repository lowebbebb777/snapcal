package com.google.android.datatransport.cct;

import com.google.android.datatransport.Encoding;
import com.google.android.datatransport.runtime.EncodedDestination;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Binary-compatible, network-disabled destination used by ML Kit's optional telemetry bridge.
 *
 * <p>The bundled Japanese OCR model is fully on-device, but ML Kit initializes a DataTransport
 * destination while creating its recognizer. SnapCal deliberately excludes the real CCT backend;
 * this class satisfies that initialization contract without carrying an endpoint, API key, or
 * transport extras. DataTransport therefore has no registered backend to deliver telemetry to.
 */
public final class CCTDestination implements EncodedDestination {
    private static final Set<Encoding> SUPPORTED_ENCODINGS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(Encoding.of("proto"), Encoding.of("json")))
    );

    public static final CCTDestination INSTANCE = new CCTDestination(null, null);
    public static final CCTDestination LEGACY_INSTANCE = INSTANCE;

    public CCTDestination(String ignoredEndPoint, String ignoredApiKey) {
        // Intentionally discard all destinations so this compatibility class cannot send data.
    }

    @Override
    public String getName() {
        return "snapcal-disabled-telemetry";
    }

    @Override
    public byte[] getExtras() {
        return null;
    }

    @Override
    public Set<Encoding> getSupportedEncodings() {
        return SUPPORTED_ENCODINGS;
    }

    public String getAPIKey() {
        return null;
    }

    public String getEndPoint() {
        return null;
    }

    public byte[] asByteArray() {
        return null;
    }

    public static CCTDestination fromByteArray(byte[] ignoredExtras) {
        return INSTANCE;
    }
}
