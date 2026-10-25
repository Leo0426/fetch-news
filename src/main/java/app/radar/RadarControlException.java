package app.radar;

public class RadarControlException extends RuntimeException {
    private final String state;

    public RadarControlException(String state, String message) {
        super(message);
        this.state = state;
    }

    public String state() {
        return state;
    }
}
