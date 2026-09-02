package ninja.samryecroft.returnhome.tracker.dashboard;

/**
 * One "Needs attention" tile. {@code tone} is one of "urgent"/"warn"/"" (never colour alone - label
 * and detail always carry the same information as the tone).
 */
public record LiveTile(String label, String value, String detail, String href, String linkText, String tone) {
}
