package app.core;

/**
 * Outcome of checking one credential against its upstream service.
 *
 * @param label   which credential was checked, as shown to the admin
 * @param valid   whether the upstream accepted it
 * @param message what the upstream said, phrased for the admin
 */
public record CredentialCheck(String label, boolean valid, String message) {
    public static CredentialCheck ok(String label, String message) {
        return new CredentialCheck(label, true, message);
    }

    public static CredentialCheck failed(String label, String message) {
        return new CredentialCheck(label, false, message);
    }
}
