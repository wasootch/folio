package ca.scottadams.folio.ui;

import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import java.text.Collator;
import java.util.ArrayList;
import java.util.List;

/**
 * Table model backing the file list: folders/locations first, then one row
 * per EPUB with columns for the parsed metadata and the live rename preview.
 */
final class BookTableModel extends AbstractTableModel {

    private static final String[] COLUMNS = {"File", "Title", "Author(s)", "Series", "#", "New Name"};

    /**
     * What a cell holds. {@code location} is non-null for folder/location rows
     * so the renderer can draw an icon and the sorter can keep them above files.
     */
    record Cell(String text, Location location) {
        @Override
        public String toString() {
            return text;
        }
    }

    private List<BookRow> rows = new ArrayList<>();

    /**
     * A sorter that always keeps folders above files, whichever way the
     * column is sorted (like Explorer), and sorts text with the locale's rules.
     */
    TableRowSorter<BookTableModel> createSorter() {
        TableRowSorter<BookTableModel> sorter = new TableRowSorter<>(this);
        Collator collator = Collator.getInstance();
        for (int column = 0; column < COLUMNS.length; column++) {
            final int sortColumn = column;
            sorter.setComparator(column, (a, b) -> {
                Cell x = (Cell) a;
                Cell y = (Cell) b;
                boolean xFolder = x.location() != null;
                boolean yFolder = y.location() != null;
                if (xFolder != yFolder) {
                    int foldersFirst = xFolder ? -1 : 1;
                    // The sorter negates comparator results for descending order; cancel that out.
                    return isDescending(sorter, sortColumn) ? -foldersFirst : foldersFirst;
                }
                return collator.compare(x.text(), y.text());
            });
        }
        return sorter;
    }

    private static boolean isDescending(TableRowSorter<?> sorter, int column) {
        for (RowSorter.SortKey key : sorter.getSortKeys()) {
            if (key.getColumn() == column) {
                return key.getSortOrder() == SortOrder.DESCENDING;
            }
        }
        return false;
    }

    void clear() {
        rows = new ArrayList<>();
        fireTableDataChanged();
    }

    void addRows(List<BookRow> added) {
        if (added.isEmpty()) {
            return;
        }
        int first = rows.size();
        rows.addAll(added);
        fireTableRowsInserted(first, rows.size() - 1);
    }

    List<BookRow> getRows() {
        return rows;
    }

    BookRow getRow(int modelIndex) {
        return rows.get(modelIndex);
    }

    void refreshPreviews() {
        fireTableDataChanged();
    }

    /** Redraws one row (e.g. after its metadata was edited) without disturbing the others. */
    void rowChanged(BookRow row) {
        int index = rows.indexOf(row);
        if (index >= 0) {
            fireTableRowsUpdated(index, index);
        }
    }

    @Override
    public int getRowCount() {
        return rows.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        BookRow row = rows.get(rowIndex);
        if (row.isLocation()) {
            return new Cell(columnIndex == 0 ? row.target.displayName() : "", row.target);
        }
        return new Cell(textAt(row, columnIndex), null);
    }

    private static String textAt(BookRow row, int columnIndex) {
        if (row.error != null) {
            return switch (columnIndex) {
                case 0 -> row.file.getFileName().toString();
                case 1 -> "(unreadable: " + row.error + ")";
                default -> "";
            };
        }

        var meta = row.metadata;
        return switch (columnIndex) {
            case 0 -> row.file.getFileName() + (row.isEdited() ? "  (edited)" : "");
            case 1 -> meta.title() == null ? "" : meta.title();
            case 2 -> meta.authorsJoined();
            case 3 -> meta.series() == null ? "" : meta.series();
            case 4 -> meta.seriesIndex() == null ? "" : formatIndex(meta.seriesIndex());
            case 5 -> row.newBaseName == null ? "" : row.newBaseName + row.extension();
            default -> "";
        };
    }

    private static String formatIndex(double index) {
        return index == Math.floor(index) ? String.valueOf((int) index) : String.valueOf(index);
    }
}
