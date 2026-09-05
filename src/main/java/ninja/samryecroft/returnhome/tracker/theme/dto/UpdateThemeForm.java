package ninja.samryecroft.returnhome.tracker.theme.dto;

import jakarta.validation.constraints.Pattern;

public class UpdateThemeForm {

    private static final String HEX_COLOR = "^#[0-9A-Fa-f]{6}$";

    @Pattern(regexp = HEX_COLOR, message = "Must be a hex colour, e.g. #F36E2A")
    private String primaryColor;


    public String getPrimaryColor() {
        return primaryColor;
    }

    public void setPrimaryColor(String primaryColor) {
        this.primaryColor = primaryColor;
    }

}
