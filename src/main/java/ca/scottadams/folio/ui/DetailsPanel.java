package ca.scottadams.folio.ui;

import ca.scottadams.folio.epub.EpubMetadataReader;
import ca.scottadams.folio.model.BookMetadata;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The right-hand side of the window for the selected book:
 * <ul>
 *   <li>the <b>proposed name</b> the file would be renamed to, in its own box;</li>
 *   <li>a <b>Metadata</b> tab with editable fields (long values wrap), and a <b>Raw</b> tab
 *       showing the OPF {@code <metadata>} block exactly as stored in the EPUB;</li>
 *   <li>Revert / Save buttons for edits.</li>
 * </ul>
 * Edits update {@link BookRow#metadata} immediately (so the proposed name follows along); they
 * only reach the EPUB file when the user presses Save.
 */
final class DetailsPanel extends JPanel {

    /** Receives what the user does in the panel. */
    interface Listener {
        /** A field was edited; {@code row.metadata} already holds the new values. */
        void metadataEdited(BookRow row);

        /** Discard unsaved edits on these books (one, or all selected in multi-edit mode). */
        void revertRequested(List<BookRow> rows);

        /** Write the edited books to their EPUBs. */
        void saveRequested(List<BookRow> rows);

        /** Multi-edit: set these fields on every one of {@code rows} (values: List for authors, else String/null). */
        void bulkApplyRequested(List<BookRow> rows, Map<BulkEdit, Object> changes);
    }

    private static final String CARD_MESSAGE = "message";
    private static final String CARD_FORM = "form";

    private final Listener listener;

    private final JTextArea proposedArea = readOnlyArea(3);

    private final JTabbedPane tabs = new JTabbedPane();
    private final CardLayout metadataCards = new CardLayout();
    private final JPanel metadataCardPanel = new JPanel(metadataCards);
    private final JTextArea messageArea = readOnlyArea(4);

    private final JTextArea fileArea = readOnlyArea(1);
    private final JTextArea folderArea = readOnlyArea(2);
    private final JTextArea titleArea = editableArea(2, true);
    private final JTextArea authorsArea = editableArea(2, false);
    private final JTextArea seriesArea = editableArea(1, true);
    private final JTextField indexField = new PlaceholderField();
    private final JTextArea publisherArea = editableArea(1, true);
    private final JTextField dateField = new PlaceholderField();
    private final JTextField languageField = new PlaceholderField();
    private final JTextArea identifierArea = editableArea(1, true);
    private final JTextArea descriptionArea = editableArea(8, false);

    private final JTextArea rawArea = readOnlyArea(10);

    private final JButton revertButton = new JButton("Revert");
    private final JButton saveButton = Theme.primary(new JButton("Save to EPUB"));

    // Multi-edit form: only the fields that make sense across books.
    private final Map<BulkEdit, Object> multiInitial = new EnumMap<>(BulkEdit.class);
    private final JButton applyButton = new JButton("Apply");

    private BookRow currentRow;
    /** The books shown in multi-edit mode (two or more); empty otherwise. */
    private List<BookRow> currentRows = List.of();
    private int currentCount = -1;
    private boolean populating;

    DetailsPanel(Listener listener) {
        this.listener = listener;
        setLayout(new BorderLayout(0, 4));

        JScrollPane proposedScroll = new JScrollPane(proposedArea,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        proposedScroll.setBorder(BorderFactory.createTitledBorder("Proposed name"));
        proposedScroll.setPreferredSize(new Dimension(0, 84));
        proposedArea.setFont(proposedArea.getFont().deriveFont(Font.BOLD));
        add(proposedScroll, BorderLayout.NORTH);

        metadataCardPanel.add(new JScrollPane(messageArea), CARD_MESSAGE);
        metadataCardPanel.add(buildFormScroll(), CARD_FORM);
        tabs.addTab("Metadata", metadataCardPanel);
        tabs.addTab("Raw", buildRawTab());
        tabs.setToolTipText(null);
        tabs.addChangeListener(e -> {
            if (isRawTabSelected()) {
                loadRaw();
            }
        });
        add(tabs, BorderLayout.CENTER);

        revertButton.setToolTipText("Discard unsaved edits and go back to what is stored in the file");
        revertButton.addActionListener(e -> {
            List<BookRow> rows = shownRows();
            if (!rows.isEmpty()) {
                listener.revertRequested(rows);
            }
        });
        saveButton.setToolTipText("Write the edited values into the EPUB's OPF (Ctrl+S). The original is kept as <name>.epub.bak");
        saveButton.addActionListener(e -> {
            List<BookRow> rows = shownRows();
            if (!rows.isEmpty()) {
                listener.saveRequested(rows);
            }
        });
        applyButton.setToolTipText("Several books selected: set the changed fields on all of them");
        applyButton.addActionListener(e -> {
            Map<BulkEdit, Object> changes = multiChanges();
            if (!changes.isEmpty()) {
                listener.bulkApplyRequested(currentRows, changes);
            }
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        buttons.add(revertButton);
        buttons.add(applyButton);
        buttons.add(saveButton);
        add(buttons, BorderLayout.SOUTH);

        installEditListeners();
        showMessage("Select a book to view or edit its metadata.");
        refreshButtons();
    }

    // ------------------------------------------------------------------ public API

    /**
     * Shows {@code row} (when exactly one item is selected) or an explanatory message. Does
     * nothing if the same selection is already shown, so a refresh never disturbs typing.
     */
    void showRow(BookRow row, int selectionCount) {
        if (row == currentRow && selectionCount == currentCount && currentRows.isEmpty()) {
            return;
        }
        currentRow = row;
        currentRows = List.of();
        currentCount = selectionCount;
        reload();
    }

    /**
     * Multi-edit mode for two or more books. Does nothing if the same books are already shown, so
     * a refresh never wipes what the user has typed.
     */
    void showBooks(List<BookRow> books, int selectionCount) {
        if (currentRow == null && books.equals(currentRows) && selectionCount == currentCount) {
            return;
        }
        currentRow = null;
        currentRows = List.copyOf(books);
        currentCount = selectionCount;
        reload();
    }

    /** Re-reads every field from the current row (after Revert or Save). */
    void reload() {
        populating = true;
        try {
            if (!currentRows.isEmpty()) {
                fillMulti();
                metadataCards.show(metadataCardPanel, CARD_FORM);
            } else if (currentCount == 0 || currentRow == null && currentCount < 2) {
                showMessage("Select a book to view or edit its metadata.");
            } else if (currentCount > 1) {
                showMessage(currentCount + " items selected.\n\nSelect a single book to view or edit its metadata.");
            } else if (currentRow.isLocation()) {
                showMessage("Double-click (or press Enter) to open:\n" + currentRow.target.displayName());
            } else if (currentRow.error != null) {
                showMessage("File: " + currentRow.file.getFileName() + "\nFolder: " + currentRow.file.getParent()
                        + "\n\nCould not read metadata:\n" + currentRow.error);
            } else {
                fill(currentRow);
                setMultiMode(false);
                metadataCards.show(metadataCardPanel, CARD_FORM);
            }
        } finally {
            populating = false;
        }
        refreshButtons();
        if (isRawTabSelected()) {
            loadRaw();
        }
    }

    void setProposedName(String name) {
        proposedArea.setText(name == null || name.isBlank() ? "" : name);
        proposedArea.setCaretPosition(0);
    }

    /** The row currently shown, or null. */
    BookRow currentRow() {
        return currentRow;
    }

    /**
     * Ctrl+S: saves the book(s) shown. In multi-edit mode, typed-but-unapplied changes are applied
     * first (which may ask about overwriting); if that is declined nothing is saved.
     */
    void saveShown() {
        if (applyButton.isEnabled()) {
            applyButton.doClick(0);
            if (applyButton.isEnabled()) {
                return;
            }
        }
        if (saveButton.isEnabled()) {
            saveButton.doClick(0);
        }
    }

    /** After a save finished: {@code refill} re-reads the fields (the file read back differently). */
    void afterSave(boolean refill) {
        if (refill) {
            reload();
            return;
        }
        refreshButtons();
        if (isRawTabSelected()) {
            loadRaw();
        }
    }

    void refreshButtons() {
        List<BookRow> rows = shownRows();
        long edited = rows.stream().filter(BookRow::needsSave).count();
        long revertable = rows.stream().filter(r -> r.isEdited() && r.saving == null).count();
        if (currentRows.isEmpty()) {
            // Apply only means something when several books are selected.
            applyButton.setEnabled(false);
            applyButton.setText("Apply");
        }
        revertButton.setEnabled(revertable > 0);
        saveButton.setEnabled(edited > 0);
        saveButton.setText(currentRows.isEmpty() ? "Save to EPUB" : "Save " + edited + " to EPUB");
    }

    /** The books Revert/Save act on: the single book shown, or the multi-edit selection. */
    private List<BookRow> shownRows() {
        if (!currentRows.isEmpty()) {
            return currentRows;
        }
        return currentRow != null ? List.of(currentRow) : List.of();
    }

    /** The books currently shown in multi-edit mode (empty otherwise). */
    List<BookRow> currentRows() {
        return currentRows;
    }

    // ------------------------------------------------------------------ form <-> row

    private void showMessage(String text) {
        messageArea.setText(text);
        messageArea.setCaretPosition(0);
        metadataCards.show(metadataCardPanel, CARD_MESSAGE);
    }

    private void fill(BookRow row) {
        BookMetadata m = row.metadata;
        setText(fileArea, row.file.getFileName().toString());
        setText(folderArea, String.valueOf(row.file.getParent()));
        setText(titleArea, nullToEmpty(m.title()));
        setText(authorsArea, String.join("\n", m.authors()));
        setText(seriesArea, nullToEmpty(m.series()));
        setText(indexField, m.seriesIndex() == null ? "" : formatIndex(m.seriesIndex()));
        setText(publisherArea, nullToEmpty(m.publisher()));
        setText(dateField, nullToEmpty(m.publishedDate()));
        setText(languageField, nullToEmpty(m.language()));
        setText(identifierArea, nullToEmpty(m.identifier()));
        setText(descriptionArea, nullToEmpty(m.description()));
        indexField.setForeground(UIManager.getColor("TextField.foreground"));
    }

    private static void setText(JTextComponent component, String text) {
        component.setText(text);
        component.setCaretPosition(0);
    }

    /** Called for every keystroke in an editable field. */
    private void fieldEdited() {
        if (populating) {
            return;
        }
        if (!currentRows.isEmpty()) {
            multiEdited();
            return;
        }
        if (currentRow == null || currentRow.metadata == null) {
            return;
        }
        BookMetadata previous = currentRow.metadata;
        Double index = previous.seriesIndex();
        String indexText = indexField.getText().trim();
        if (indexText.isEmpty()) {
            index = null;
            indexField.setForeground(UIManager.getColor("TextField.foreground"));
        } else {
            try {
                index = Double.parseDouble(indexText);
                indexField.setForeground(UIManager.getColor("TextField.foreground"));
            } catch (NumberFormatException e) {
                // Keep the last valid number and flag the field until it parses.
                indexField.setForeground(Theme.DANGER);
            }
        }

        currentRow.metadata = new BookMetadata(
                clean(titleArea.getText()),
                lines(authorsArea.getText()),
                clean(seriesArea.getText()),
                index,
                clean(publisherArea.getText()),
                clean(dateField.getText()),
                clean(languageField.getText()),
                clean(identifierArea.getText()),
                clean(descriptionArea.getText()));
        refreshButtons();
        listener.metadataEdited(currentRow);
    }

    private static String clean(String text) {
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static List<String> lines(String text) {
        List<String> result = new ArrayList<>();
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                result.add(line.trim());
            }
        }
        return result;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String formatIndex(double index) {
        return index == Math.floor(index) ? String.valueOf((int) index) : String.valueOf(index);
    }

    private void installEditListeners() {
        DocumentListener listener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                fieldEdited();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                fieldEdited();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                fieldEdited();
            }
        };
        for (JTextComponent field : List.of(titleArea, authorsArea, seriesArea, indexField, publisherArea,
                dateField, languageField, identifierArea, descriptionArea)) {
            field.getDocument().addDocumentListener(listener);
        }
    }

    // ------------------------------------------------------------------ multi-edit

    private JTextComponent multiField(BulkEdit field) {
        return switch (field) {
            case SERIES -> seriesArea;
            case AUTHORS -> authorsArea;
            case PUBLISHER -> publisherArea;
            case LANGUAGE -> languageField;
        };
    }

    /** Enables only the fields that make sense across several books; the rest are greyed out in place. */
    private void setMultiMode(boolean multi) {
        for (JTextComponent c : List.of(titleArea, indexField, dateField, identifierArea, descriptionArea)) {
            c.setEnabled(!multi);
        }
        if (!multi) {
            applyButton.setEnabled(false);
            applyButton.setText("Apply");
            for (JTextComponent c : List.of(titleArea, authorsArea, seriesArea, indexField, publisherArea,
                    dateField, languageField, identifierArea, descriptionArea)) {
                setPlaceholder(c, null);
            }
        }
    }

    /** Fills the form from {@link #currentRows}: the common value, or blank (with a hint) when books differ. */
    private void fillMulti() {
        int n = currentRows.size();
        setMultiMode(true);
        long folders = currentRows.stream().map(r -> r.file.getParent()).distinct().count();
        setText(fileArea, n + " books selected");
        setText(folderArea, folders == 1 ? String.valueOf(currentRows.get(0).file.getParent())
                : "(" + folders + " different folders)");
        for (JTextComponent c : List.of(titleArea, indexField, dateField, identifierArea, descriptionArea)) {
            setText(c, "");
            setPlaceholder(c, "(different for each book)");
        }
        indexField.setForeground(UIManager.getColor("TextField.foreground"));
        multiInitial.clear();
        for (BulkEdit field : BulkEdit.values()) {
            Map<Object, Integer> distinct = new LinkedHashMap<>();
            for (BookRow book : currentRows) {
                distinct.merge(normalize(field, field.get(book.metadata)), 1, Integer::sum);
            }
            JTextComponent component = multiField(field);
            if (distinct.size() == 1) {
                Object value = distinct.keySet().iterator().next();
                multiInitial.put(field, value);
                setText(component, field == BulkEdit.AUTHORS ? String.join("\n", asList(value)) : (String) value);
                setPlaceholder(component, BulkEdit.isEmpty(value) ? "(empty in all " + n + " books)" : null);
            } else {
                multiInitial.put(field, normalize(field, null));
                setText(component, "");
                List<String> shown = new ArrayList<>();
                for (Map.Entry<Object, Integer> e : distinct.entrySet()) {
                    shown.add(BulkEdit.display(e.getKey()) + " (" + e.getValue() + ")");
                }
                setPlaceholder(component, "Differs: " + String.join(" | ", shown) + " \u2014 type to replace all");
            }
        }
        applyButton.setText("Apply to " + n + " books");
        applyButton.setEnabled(false);
    }

    private static void setPlaceholder(JTextComponent component, String text) {
        component.putClientProperty("placeholder", text);
        component.setToolTipText(text);
        component.repaint();
    }

    private static void paintPlaceholder(JTextComponent c, Graphics g) {
        Object text = c.getClientProperty("placeholder");
        if (text == null || c.getDocument().getLength() > 0) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setColor(UIManager.getColor("Label.disabledForeground"));
        g2.setFont(c.getFont().deriveFont(Font.ITALIC));
        Insets in = c.getInsets();
        g2.setClip(in.left, 0, Math.max(0, c.getWidth() - in.left - in.right), c.getHeight());
        g2.drawString(String.valueOf(text), in.left, in.top + g2.getFontMetrics().getAscent());
        g2.dispose();
    }

    private static final class PlaceholderField extends JTextField {
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            paintPlaceholder(this, g);
        }
    }

    private static final class PlaceholderArea extends JTextArea {
        PlaceholderArea(int rows, int columns) {
            super(rows, columns);
        }

        /** Unlike a JTextField, a disabled JTextArea keeps its white background; grey it like the others. */
        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            Color background = UIManager.getColor(enabled ? "TextArea.background" : "TextField.inactiveBackground");
            if (background == null && !enabled) {
                background = UIManager.getColor("Panel.background");
            }
            if (background != null) {
                setBackground(background);
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            paintPlaceholder(this, g);
        }
    }

    private void multiEdited() {
        if (populating) {
            return;
        }
        applyButton.setEnabled(!multiChanges().isEmpty());
    }

    /** The multi-edit fields whose text differs from what they were filled with. */
    private Map<BulkEdit, Object> multiChanges() {
        Map<BulkEdit, Object> changes = new EnumMap<>(BulkEdit.class);
        if (currentRows.isEmpty()) {
            return changes;
        }
        for (BulkEdit field : BulkEdit.values()) {
            JTextComponent component = multiField(field);
            Object now = field == BulkEdit.AUTHORS ? lines(component.getText()) : clean(component.getText());
            Object before = multiInitial.get(field);
            boolean same = BulkEdit.isEmpty(now) && BulkEdit.isEmpty(before) || Objects.equals(now, before);
            if (!same) {
                changes.put(field, now);
            }
        }
        return changes;
    }

    private static Object normalize(BulkEdit field, Object value) {
        if (field == BulkEdit.AUTHORS) {
            return value == null ? List.of() : value;
        }
        return value instanceof String s ? clean(s) : value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> asList(Object value) {
        return (List<String>) value;
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ------------------------------------------------------------------ raw tab

    private boolean isRawTabSelected() {
        return tabs.getSelectedIndex() == 1;
    }

    private void loadRaw() {
        BookRow row = currentRow;
        if (row == null || currentCount != 1 || row.isLocation() || row.file == null) {
            setText(rawArea, "");
            return;
        }
        if (row.rawMetadata != null) {
            setText(rawArea, row.rawMetadata);
            return;
        }
        setText(rawArea, "Loading…");
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return EpubMetadataReader.readRawMetadata(row.file);
            }

            @Override
            protected void done() {
                String text;
                try {
                    text = get();
                    row.rawMetadata = text;
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    text = "Could not read the raw metadata:\n" + cause.getMessage();
                }
                if (row == currentRow && isRawTabSelected()) {
                    setText(rawArea, text);
                }
            }
        }.execute();
    }

    private JComponent buildRawTab() {
        JTextArea note = readOnlyArea(2);
        note.setText("The OPF <metadata> block exactly as stored in the EPUB. "
                + "Unsaved edits are not reflected here until you save.");
        rawArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 0, 4));
        panel.add(note, BorderLayout.NORTH);
        panel.add(new JScrollPane(rawArea, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER), BorderLayout.CENTER);
        return panel;
    }

    // ------------------------------------------------------------------ form layout

    private JComponent buildFormScroll() {
        FormPanel form = new FormPanel();
        form.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        int[] row = {0};
        addField(form, row, "File", scroll(fileArea), false);
        addField(form, row, "Folder", scroll(folderArea), false);
        addField(form, row, "Title", scroll(titleArea), false);
        addField(form, row, "Author(s) — one per line", scroll(authorsArea), false);
        addField(form, row, "Series", scroll(seriesArea), false);
        addField(form, row, "Series #", indexField, false);
        addField(form, row, "Publisher", scroll(publisherArea), false);
        addField(form, row, "Date", dateField, false);
        addField(form, row, "Language", languageField, false);
        addField(form, row, "Identifier", scroll(identifierArea), false);
        addField(form, row, "Description", scroll(descriptionArea), true);

        JScrollPane scroll = new JScrollPane(form, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private static void addField(JPanel form, int[] row, String label, JComponent field, boolean grow) {
        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = 0;
        labelConstraints.gridy = row[0]++;
        labelConstraints.anchor = GridBagConstraints.WEST;
        labelConstraints.insets = new Insets(labelConstraints.gridy == 0 ? 0 : 8, 0, 2, 0);
        JLabel jLabel = new JLabel(label);
        jLabel.setFont(jLabel.getFont().deriveFont(Font.BOLD));
        form.add(jLabel, labelConstraints);

        GridBagConstraints fieldConstraints = new GridBagConstraints();
        fieldConstraints.gridx = 0;
        fieldConstraints.gridy = row[0]++;
        fieldConstraints.weightx = 1;
        fieldConstraints.weighty = grow ? 1 : 0;
        fieldConstraints.fill = grow ? GridBagConstraints.BOTH : GridBagConstraints.HORIZONTAL;
        form.add(field, fieldConstraints);
    }

    private static JScrollPane scroll(JTextArea area) {
        JScrollPane pane = new JScrollPane(area, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        if (!area.isEditable()) {
            pane.setBorder(BorderFactory.createEmptyBorder());
        }
        return pane;
    }

    /** A wrapping text area the user can edit. Single-line fields turn pasted newlines into spaces. */
    private static JTextArea editableArea(int rows, boolean singleLine) {
        JTextArea area = new PlaceholderArea(rows, 20);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(UIManager.getFont("TextField.font"));
        // Tab should move between fields, not insert a tab character.
        area.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, null);
        area.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, null);
        if (singleLine) {
            ((AbstractDocument) area.getDocument()).setDocumentFilter(new SingleLineFilter());
        }
        return area;
    }

    private static JTextArea readOnlyArea(int rows) {
        JTextArea area = new JTextArea(rows, 20);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setFont(UIManager.getFont("TextField.font"));
        area.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        return area;
    }

    /** Replaces line breaks with spaces so one-line fields can't grow extra lines. */
    private static final class SingleLineFilter extends DocumentFilter {
        @Override
        public void insertString(FilterBypass fb, int offset, String text, AttributeSet attrs)
                throws BadLocationException {
            super.insertString(fb, offset, flatten(text), attrs);
        }

        @Override
        public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs)
                throws BadLocationException {
            super.replace(fb, offset, length, text == null ? null : flatten(text), attrs);
        }

        private static String flatten(String text) {
            return text == null ? null : text.replaceAll("\\R", " ");
        }
    }

    /** Tracks the viewport's width (so text wraps instead of scrolling sideways) and fills its height when it can. */
    private static final class FormPanel extends JPanel implements Scrollable {
        FormPanel() {
            super(new GridBagLayout());
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 64;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return getParent() instanceof JViewport viewport && viewport.getHeight() > getPreferredSize().height;
        }
    }
}
