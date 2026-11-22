package ca.scottadams.folio.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;

import javax.swing.JFrame;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;
import java.util.Map;

/**
 * Folio's look: a near-black window with a neon cyan accent, rounded controls and generous spacing,
 * built on FlatLaf's dark theme. Colours are defined once here so the rest of the UI just uses
 * {@link UIManager} keys.
 */
public final class Theme {

    /** Neon cyan: focus rings, selection, the primary button. */
    public static final Color ACCENT = new Color(0x22E5FF);
    /** Magenta, for warnings and invalid input. */
    public static final Color DANGER = new Color(0xFF4D8D);

    /** Neon green: the file name already matches the rename pattern. */
    public static final Color MATCH = new Color(0x4ADE80);
    /** Amber: the file would be renamed. */
    public static final Color MISMATCH = new Color(0xFFB454);

    private static final String BACKGROUND = "#0D0F17";
    private static final String SURFACE = "#141824";
    private static final String SURFACE_HIGH = "#1B2030";
    private static final String BORDER = "#262C40";
    private static final String FOREGROUND = "#D6DBEA";
    private static final String MUTED = "#7C86A5";

    private Theme() {
    }

    /** Installs the theme; call before creating any Swing component. */
    public static void install() {
        FlatLaf.setGlobalExtraDefaults(Map.of(
                "@background", BACKGROUND,
                "@foreground", FOREGROUND,
                "@accentColor", "#22E5FF"));
        System.setProperty("flatlaf.menuBarEmbedded", "true");
        JFrame.setDefaultLookAndFeelDecorated(true);
        FlatDarkLaf.setup();

        // Shape: soft rounded corners everywhere.
        UIManager.put("Component.arc", 10);
        UIManager.put("Button.arc", 10);
        UIManager.put("TextComponent.arc", 10);
        UIManager.put("CheckBox.arc", 6);
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("Component.innerFocusWidth", 0);
        UIManager.put("Component.focusColor", ACCENT);
        UIManager.put("Component.borderColor", color(BORDER));
        UIManager.put("Component.disabledBorderColor", color(BORDER));

        // Surfaces.
        UIManager.put("Panel.background", color(BACKGROUND));
        UIManager.put("TextField.background", color(SURFACE));
        UIManager.put("TextArea.background", color(SURFACE));
        UIManager.put("TextField.inactiveBackground", color(BACKGROUND));
        UIManager.put("TextArea.inactiveBackground", color(BACKGROUND));
        UIManager.put("TextComponent.selectionBackground", new Color(0x22, 0xE5, 0xFF, 90));
        UIManager.put("Label.disabledForeground", color(MUTED));
        UIManager.put("Button.background", color(SURFACE_HIGH));
        UIManager.put("Button.hoverBackground", color("#252C42"));
        UIManager.put("Button.pressedBackground", color("#2E3652"));
        UIManager.put("Button.default.background", ACCENT);
        UIManager.put("Button.default.foreground", color(BACKGROUND));
        UIManager.put("Button.default.hoverBackground", ACCENT.brighter());
        UIManager.put("Button.disabledBackground", color(SURFACE));
        UIManager.put("Button.disabledText", color("#4C5470"));
        UIManager.put("Button.margin", new java.awt.Insets(5, 14, 5, 14));
        UIManager.put("ToolBar.background", color(BACKGROUND));
        UIManager.put("ToolBar.borderMargins", new java.awt.Insets(5, 8, 5, 8));
        UIManager.put("Separator.foreground", color(BORDER));

        // Table: airy rows, no grid, accent selection.
        UIManager.put("Table.background", color(BACKGROUND));
        UIManager.put("Table.rowHeight", 30);
        UIManager.put("Table.showHorizontalLines", false);
        UIManager.put("Table.showVerticalLines", false);
        UIManager.put("Table.intercellSpacing", new java.awt.Dimension(0, 0));
        UIManager.put("Table.selectionBackground", new Color(0x22, 0xE5, 0xFF, 48));
        UIManager.put("Table.selectionForeground", Color.WHITE);
        UIManager.put("Table.selectionInactiveBackground", new Color(0x22, 0xE5, 0xFF, 28));
        UIManager.put("Table.selectionInactiveForeground", Color.WHITE);
        UIManager.put("Table.cellMargins", new java.awt.Insets(2, 10, 2, 10));
        UIManager.put("TableHeader.background", color(SURFACE));
        UIManager.put("TableHeader.foreground", color(MUTED));
        UIManager.put("TableHeader.bottomSeparatorColor", color(BORDER));
        UIManager.put("TableHeader.height", 32);
        UIManager.put("TableHeader.font", UIManager.getFont("TableHeader.font").deriveFont(Font.BOLD, 12f));

        // Scroll bars and split panes: slim and quiet.
        UIManager.put("ScrollBar.width", 11);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.thumbInsets", new java.awt.Insets(2, 2, 2, 2));
        UIManager.put("ScrollBar.track", color(BACKGROUND));
        UIManager.put("ScrollBar.thumb", color("#2A3150"));
        UIManager.put("ScrollBar.hoverThumbColor", color("#3A4470"));
        UIManager.put("ScrollPane.border", javax.swing.BorderFactory.createLineBorder(color(BORDER)));
        UIManager.put("SplitPane.dividerSize", 6);
        UIManager.put("SplitPaneDivider.gripColor", color(BORDER));

        // Tabs, menus, tooltips.
        UIManager.put("TabbedPane.selectedBackground", color(BACKGROUND));
        UIManager.put("TabbedPane.underlineColor", ACCENT);
        UIManager.put("TabbedPane.hoverColor", color(SURFACE_HIGH));
        UIManager.put("TabbedPane.tabHeight", 34);
        UIManager.put("MenuBar.background", color(BACKGROUND));
        UIManager.put("TitlePane.background", color(BACKGROUND));
        UIManager.put("TitlePane.inactiveBackground", color(BACKGROUND));
        UIManager.put("PopupMenu.background", color(SURFACE));
        UIManager.put("PopupMenu.borderColor", color(BORDER));
        UIManager.put("MenuItem.selectionBackground", new Color(0x22, 0xE5, 0xFF, 48));
        UIManager.put("MenuItem.selectionForeground", Color.WHITE);
        UIManager.put("ToolTip.background", color(SURFACE_HIGH));
        UIManager.put("ToolTip.foreground", color(FOREGROUND));
        UIManager.put("ProgressBar.foreground", ACCENT);
    }

    /** Styles a button as the neon primary action (greyed out like any other button when disabled). */
    public static <T extends javax.swing.JButton> T primary(T button) {
        button.putClientProperty("FlatLaf.style", "background:#22E5FF;foreground:#0D0F17;"
                + "hoverBackground:#6AF0FF;pressedBackground:#14B8D0;"
                + "disabledBackground:#1B2030;disabledText:#7C86A5");
        return button;
    }

    /** For the primary action inside a toolbar, where FlatLaf draws buttons borderless: neon bold text. */
    public static <T extends javax.swing.JButton> T accentText(T button) {
        button.putClientProperty("FlatLaf.style", "foreground:#22E5FF;font:bold;disabledText:#4C5470");
        return button;
    }

    private static Color color(String hex) {
        return Color.decode(hex);
    }
}
