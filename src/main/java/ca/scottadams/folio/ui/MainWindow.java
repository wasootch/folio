package ca.scottadams.folio.ui;

import ca.scottadams.folio.AppOptions;
import ca.scottadams.folio.epub.EpubMetadataReader;
import ca.scottadams.folio.epub.EpubMetadataWriter;
import ca.scottadams.folio.epub.EpubParseException;
import ca.scottadams.folio.logging.AppLogging;
import ca.scottadams.folio.model.BookMetadata;
import ca.scottadams.folio.rename.RenameTemplate;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The application's single window: breadcrumb navigation, a file list that
 * shows folders (double-click to open) and the EPUBs in the current folder
 * with their parsed metadata, a rename-template field with a live preview
 * column, buttons to perform the rename, and a log panel showing what the
 * app is doing.
 */
public final class MainWindow extends JFrame {

    private static final Logger LOG = Logger.getLogger(AppLogging.LOGGER_NAME);

    /** A bare server name such as {@code \\SERVER_NAME}, as opposed to a share path. */
    private static final Pattern SERVER_ONLY = Pattern.compile("^[\\\\/]{2}([^\\\\/]+)[\\\\/]*$");

    private final BookTableModel tableModel = new BookTableModel();
    private final JTable table = new JTable(tableModel);
    private final DetailsPanel details = new DetailsPanel(new DetailsPanel.Listener() {
        @Override
        public void metadataEdited(BookRow row) {
            onMetadataEdited(row);
        }

        @Override
        public void revertRequested(List<BookRow> rows) {
            onRevert(rows);
        }

        @Override
        public void saveRequested(List<BookRow> rows) {
            onSave(rows);
        }

        @Override
        public void bulkApplyRequested(List<BookRow> rows, Map<BulkEdit, Object> changes) {
            onBulkApply(rows, changes);
        }
    });
    private final JTextArea logArea = new JTextArea();
    private final BreadcrumbBar locationBar = new BreadcrumbBar(new BreadcrumbBar.Listener() {
        @Override
        public void navigate(Location location) {
            navigateTo(location);
        }

        @Override
        public void pathTyped(String text) {
            navigateToTypedPath(text);
        }
    });
    private final JTextField templateField = new JTextField(RenameTemplate.DEFAULT_TEMPLATE);
    private final JCheckBox recursiveCheckBox = new JCheckBox("Include subfolders");
    private final JLabel statusLabel = new JLabel("Choose a location to begin.");
    private final JButton upButton = new JButton("↑ Up");
    private final JButton browseButton = new JButton("Browse…");
    private final JButton renameButton = Theme.accentText(new JButton("Rename"));

    private Location currentLocation = Location.HOME;
    /** Bumped on every navigation so results from a superseded load are ignored. */
    private int loadGeneration;
    private SwingWorker<Void, BookRow> loader;

    private static final String APP_NAME = "Folio";
    private static final String APP_AUTHOR = "Scott Adams";

    /** Holds the table/details split, with the log panel underneath when it is shown. */
    private final JPanel centerHolder = new JPanel(new BorderLayout());
    private JSplitPane tableAndDetail;
    private JComponent logPanel;
    private final JCheckBoxMenuItem showLogItem = new JCheckBoxMenuItem("Show Log Panel");

    public MainWindow() {
        this(new AppOptions(false, null, false));
    }

    public MainWindow(AppOptions options) {
        super("Folio — EPUB Renamer");
        buildUi();
        setJMenuBar(buildMenuBar());
        installMouseNavigation();
        java.util.concurrent.atomic.AtomicBoolean discoveryUpdatePending = new java.util.concurrent.atomic.AtomicBoolean();
        NetworkDiscovery.instance().addListener(() -> {
            // Called on the discovery thread, possibly many times in a burst: coalesce into one EDT update.
            if (discoveryUpdatePending.compareAndSet(false, true)) {
                SwingUtilities.invokeLater(() -> {
                    discoveryUpdatePending.set(false);
                    onDiscoveryChanged();
                });
            }
        });
        setLogVisible(options.debug());
        Logger.getLogger(AppLogging.LOGGER_NAME).addHandler(new TextAreaLogHandler(logArea));
        // Closing asks first if there are edits that haven't been saved to their EPUBs.
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                exit();
            }
        });
        setSize(1250, 760);
        setLocationRelativeTo(null);
        SwingUtilities.invokeLater(table::requestFocusInWindow);
        if (options.startPath() != null && !options.startPath().isBlank()) {
            LOG.info(() -> "Starting at " + options.startPath());
            navigateToTypedPath(options.startPath());
        } else {
            navigateTo(Location.HOME);
        }
    }

    private void buildUi() {
        setLayout(new BorderLayout());
        add(buildToolbar(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);
    }

    /** Closes the app, first asking about edits that haven't been saved. */
    private void exit() {
        if (confirmDiscardEdits()) {
            dispose();
            System.exit(0);
        }
    }

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();

        JMenu file = new JMenu("File");
        file.setMnemonic(KeyEvent.VK_F);
        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.setMnemonic(KeyEvent.VK_X);
        exitItem.addActionListener(e -> exit());
        file.add(exitItem);
        bar.add(file);

        JMenu help = new JMenu("Help");
        help.setMnemonic(KeyEvent.VK_H);
        showLogItem.setMnemonic(KeyEvent.VK_L);
        showLogItem.addActionListener(e -> setLogVisible(showLogItem.isSelected()));
        help.add(showLogItem);
        help.addSeparator();
        JMenuItem about = new JMenuItem("About " + APP_NAME);
        about.setMnemonic(KeyEvent.VK_A);
        about.addActionListener(e -> showAbout());
        help.add(about);
        bar.add(help);
        return bar;
    }

    private void showAbout() {
        String version = MainWindow.class.getPackage().getImplementationVersion();
        JOptionPane.showMessageDialog(this,
                APP_NAME + " — EPUB Renamer\n"
                        + "Version " + (version != null ? version : "(development build)") + "\n\n"
                        + "Browse EPUB files, view and edit their metadata,\n"
                        + "and rename them from author / series / title templates.\n\n"
                        + "Author: " + APP_AUTHOR + "\n"
                        + "Java " + System.getProperty("java.version"),
                "About " + APP_NAME, JOptionPane.INFORMATION_MESSAGE);
    }

    /** Shows or hides the log panel under the file list. The log keeps collecting either way. */
    private void setLogVisible(boolean visible) {
        showLogItem.setSelected(visible);
        centerHolder.removeAll();
        if (visible) {
            JSplitPane withLog = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableAndDetail, logPanel);
            withLog.setResizeWeight(0.8);
            centerHolder.add(withLog, BorderLayout.CENTER);
            centerHolder.revalidate();
            SwingUtilities.invokeLater(() -> withLog.setDividerLocation(0.8));
        } else {
            centerHolder.add(tableAndDetail, BorderLayout.CENTER);
            centerHolder.revalidate();
        }
        centerHolder.repaint();
    }

    private JComponent buildToolbar() {
        JPanel toolbars = new JPanel();
        toolbars.setLayout(new BoxLayout(toolbars, BoxLayout.Y_AXIS));

        JToolBar pathBar = new JToolBar();
        pathBar.setFloatable(false);
        upButton.setToolTipText("Go up one level (Alt+Up or Backspace)");
        upButton.addActionListener(e -> goUp());
        pathBar.add(upButton);
        pathBar.addSeparator(new Dimension(6, 0));
        pathBar.add(locationBar);
        pathBar.addSeparator(new Dimension(6, 0));
        browseButton.setToolTipText("Pick a folder with the system folder dialog");
        browseButton.addActionListener(e -> onBrowse());
        pathBar.add(browseButton);
        recursiveCheckBox.addActionListener(e -> {
            if (currentLocation.kind() != Location.Kind.FOLDER) {
                return;
            }
            if (confirmDiscardEdits()) {
                load(currentLocation);
            } else {
                recursiveCheckBox.setSelected(!recursiveCheckBox.isSelected()); // undo the toggle
            }
        });
        pathBar.add(recursiveCheckBox);
        toolbars.add(pathBar);

        // Ctrl+L / F4 jump to the address bar for typing or pasting a path, like Explorer.
        JRootPane root = getRootPane();
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_L, java.awt.event.InputEvent.CTRL_DOWN_MASK), "focusAddressBar");
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_F4, 0), "focusAddressBar");
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, java.awt.event.InputEvent.ALT_DOWN_MASK), "goUp");
        root.getActionMap().put("focusAddressBar", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                locationBar.showEditor();
            }
        });
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_S, java.awt.event.InputEvent.CTRL_DOWN_MASK), "saveMetadata");
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, java.awt.event.InputEvent.ALT_DOWN_MASK), "goBack");
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, java.awt.event.InputEvent.ALT_DOWN_MASK), "goForward");
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), "refreshNetwork");
        root.getActionMap().put("refreshNetwork", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                refreshNetwork();
            }
        });
        root.getActionMap().put("saveMetadata", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                details.saveShown();
            }
        });
        root.getActionMap().put("goBack", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                goBack();
            }
        });
        root.getActionMap().put("goForward", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                goForward();
            }
        });
        root.getActionMap().put("goUp", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                goUp();
            }
        });

        JToolBar renameBar = new JToolBar();
        renameBar.setFloatable(false);
        renameBar.add(new JLabel("Rename pattern: "));
        templateField.setToolTipText(
                "Tokens: {title} {author} {authors} {series} {series_index} {publisher} {year} {language}");
        templateField.getDocument().addDocumentListener((SimpleDocumentListener) e -> updatePreviews());
        renameBar.add(templateField);

        JButton resetTemplate = new JButton("Reset");
        resetTemplate.addActionListener(e -> {
            templateField.setText(RenameTemplate.DEFAULT_TEMPLATE);
            updatePreviews();
        });
        renameBar.add(resetTemplate);

        renameButton.addActionListener(e -> onRename());
        renameBar.addSeparator(new Dimension(20, 0));
        renameBar.add(renameButton);
        toolbars.add(renameBar);

        return toolbars;
    }

    private JComponent buildCenter() {
        table.setRowSorter(tableModel.createSorter());
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                onSelectionChanged();
            }
        });
        table.getColumnModel().getColumn(0).setCellRenderer(new NameCellRenderer());
        table.getColumnModel().getColumn(5).setCellRenderer(new NamingCellRenderer());
        int[] widths = {300, 220, 160, 140, 40, 300};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        configureTableNavigation();
        JScrollPane tableScroll = new JScrollPane(table);

        details.setPreferredSize(new Dimension(400, 0));
        details.setMinimumSize(new Dimension(300, 0));

        tableAndDetail = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tableScroll, details);
        tableAndDetail.setResizeWeight(0.6);

        logPanel = buildLogPanel();
        centerHolder.add(tableAndDetail, BorderLayout.CENTER);
        return centerHolder;
    }

    private JComponent buildLogPanel() {
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        JScrollPane logScroll = new JScrollPane(logArea);

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Log"));
        panel.add(logScroll, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton clearButton = new JButton("Clear");
        clearButton.addActionListener(e -> logArea.setText(""));
        buttons.add(clearButton);

        JButton openLogFileButton = new JButton("Open Log File");
        openLogFileButton.addActionListener(e -> onOpenLogFile());
        buttons.add(openLogFileButton);

        panel.add(buttons, BorderLayout.SOUTH);
        panel.setPreferredSize(new Dimension(0, 140));
        return panel;
    }

    private JComponent buildStatusBar() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        panel.add(statusLabel, BorderLayout.WEST);
        return panel;
    }

    // ------------------------------------------------------------- navigation

    /** Moves to {@code location}: updates the breadcrumbs and loads its contents. */
    private void navigateTo(Location location) {
        navigateTo(location, true);
    }

    /** Where we came from / went back from, for the mouse back and forward buttons. */
    private final java.util.ArrayDeque<Location> backStack = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<Location> forwardStack = new java.util.ArrayDeque<>();
    private static final int HISTORY_LIMIT = 100;

    private boolean navigateTo(Location location, boolean recordHistory) {
        if (!confirmDiscardEdits()) {
            return false;
        }
        if (recordHistory && !location.equals(currentLocation)) {
            backStack.push(currentLocation);
            while (backStack.size() > HISTORY_LIMIT) {
                backStack.removeLast();
            }
            forwardStack.clear();
        }
        currentLocation = location;
        locationBar.setLocation(location);
        upButton.setEnabled(location.parent() != null);
        load(location);
        return true;
    }

    /** Back to the previously viewed location (mouse back button, Alt+Left). */
    private void goBack() {
        if (backStack.isEmpty()) {
            return;
        }
        Location previous = backStack.peek();
        Location here = currentLocation;
        if (navigateTo(previous, false)) {
            backStack.pop();
            forwardStack.push(here);
        }
    }

    /** Forward again after going back (mouse forward button, Alt+Right). */
    private void goForward() {
        if (forwardStack.isEmpty()) {
            return;
        }
        Location next = forwardStack.peek();
        Location here = currentLocation;
        if (navigateTo(next, false)) {
            forwardStack.pop();
            backStack.push(here);
        }
    }

    /** Mouse buttons 4 and 5 (the thumb buttons) go back and forward, like in a browser or Explorer. */
    private void installMouseNavigation() {
        java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
            if (!(event instanceof MouseEvent me) || me.getID() != MouseEvent.MOUSE_PRESSED
                    || (me.getButton() != 4 && me.getButton() != 5)
                    || !(me.getSource() instanceof Component source)
                    || SwingUtilities.getWindowAncestor(source) != this || !isActive()) {
                return;
            }
            if (me.getButton() == 4) {
                goBack();
            } else {
                goForward();
            }
        }, AWTEvent.MOUSE_EVENT_MASK);
    }

    private void goUp() {
        Location parent = currentLocation.parent();
        if (parent != null) {
            navigateTo(parent);
        }
    }

    /** Handles a path typed or pasted into the address bar, e.g. {@code \\SERVER_NAME\Books}. */
    private void navigateToTypedPath(String raw) {
        String text = raw.trim();
        // Explorer's "Copy as path" wraps the path in quotes.
        text = text.replaceAll("^\"+|\"+$", "").trim();
        if (text.isEmpty()) {
            return;
        }

        if (LocationService.isWindows()) {
            Matcher server = SERVER_ONLY.matcher(text);
            if (server.matches()) {
                navigateTo(Location.server(server.group(1)));
                return;
            }
        }
        try {
            navigateTo(Location.folder(Path.of(text).toAbsolutePath()));
        } catch (InvalidPathException e) {
            LOG.log(Level.WARNING, "Invalid path entered: " + text, e);
            JOptionPane.showMessageDialog(this, "That doesn't look like a valid path:\n" + text,
                    "Invalid Path", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static final String BROWSE_TITLE = "Choose a folder containing EPUB files";

    private Path currentFolderPath() {
        return currentLocation.kind() == Location.Kind.FOLDER ? currentLocation.path() : null;
    }

    private void onBrowse() {
        Optional<Path> chosen;
        if (NativeFolderPicker.isSupported()) {
            try {
                chosen = NativeFolderPicker.pick(this, BROWSE_TITLE, currentFolderPath());
            } catch (RuntimeException | LinkageError e) {
                LOG.log(Level.WARNING, "Native folder dialog unavailable; using the Swing chooser.", e);
                chosen = browseWithSwing();
            }
        } else {
            chosen = browseWithSwing();
        }
        chosen.ifPresent(folder -> navigateTo(Location.folder(folder)));
    }

    private Optional<Path> browseWithSwing() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle(BROWSE_TITLE);
        if (currentFolderPath() != null) {
            chooser.setCurrentDirectory(currentFolderPath().toFile());
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            return Optional.of(chooser.getSelectedFile().toPath());
        }
        return Optional.empty();
    }

    private void onOpenLogFile() {
        Path logFile = AppLogging.logFilePath();
        LOG.info(() -> "Opening log file: " + logFile);
        try {
            if (Desktop.isDesktopSupported() && Files.exists(logFile)) {
                Desktop.getDesktop().open(logFile.toFile());
                return;
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not open log file with the system viewer.", e);
        }
        JOptionPane.showMessageDialog(this, "Log file location:\n" + logFile,
                "Log File", JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * Loads {@code location} into the file list in the background. Folders
     * appear first, then EPUBs as their metadata is read, so a big or slow
     * folder is usable straight away. Navigating elsewhere cancels the load.
     */
    private void load(Location location) {
        if (loader != null) {
            loader.cancel(true);
        }
        final int generation = ++loadGeneration;
        final boolean recursive = recursiveCheckBox.isSelected();

        tableModel.clear();
        details.showRow(null, 0);
        details.setProposedName(null);
        renameButton.setEnabled(false);
        statusLabel.setText("Loading " + location.displayName() + " …");
        LOG.info(() -> "Opening " + location.displayName()
                + (location.kind() == Location.Kind.FOLDER
                ? " [" + location.path() + "] (includeSubfolders=" + recursive + ")" : ""));

        loader = new SwingWorker<>() {
            private int folderCount;
            private int bookCount;
            private int failedCount;

            @Override
            protected Void doInBackground() throws Exception {
                // All I/O happens here, off the EDT: an unreachable network path (e.g. a UNC
                // path to a host that's down) can block for a long time and must never freeze
                // the window.
                if (location.kind() != Location.Kind.FOLDER) {
                    for (Location child : LocationService.listChildren(location)) {
                        publish(BookRow.location(child));
                        folderCount++;
                    }
                    return null;
                }

                LocationService.Listing listing = LocationService.listFolder(location.path(), recursive);
                LocationService.rememberShareOf(location.path());
                for (Path folder : listing.folders()) {
                    publish(BookRow.location(Location.folder(folder)));
                    folderCount++;
                }
                LOG.info(() -> "Found " + listing.epubs().size() + " .epub file(s) and "
                        + listing.folders().size() + " folder(s) in " + location.path());

                for (Path epub : listing.epubs()) {
                    if (isCancelled()) {
                        return null;
                    }
                    try {
                        publish(new BookRow(epub, EpubMetadataReader.read(epub), null));
                    } catch (EpubParseException e) {
                        // Already logged with full detail inside EpubMetadataReader.
                        publish(new BookRow(epub, null, e.getMessage()));
                        failedCount++;
                    }
                    bookCount++;
                }
                return null;
            }

            @Override
            protected void process(List<BookRow> chunk) {
                if (generation != loadGeneration) {
                    return;
                }
                String template = templateField.getText();
                for (BookRow row : chunk) {
                    if (row.metadata != null) {
                        row.newBaseName = RenameTemplate.render(template, row.metadata);
                    }
                }
                tableModel.addRows(chunk);
                statusLabel.setText("Loading " + location.displayName() + " … "
                        + tableModel.getRowCount() + " item(s)");
            }

            @Override
            protected void done() {
                if (generation != loadGeneration) {
                    return; // superseded by a newer navigation
                }
                renameButton.setEnabled(true);
                try {
                    get();
                    if (location.kind() == Location.Kind.FOLDER) {
                        statusLabel.setText(bookCount + " EPUB file(s) and " + folderCount + " folder(s) in "
                                + location.path()
                                + (failedCount > 0 ? " (" + failedCount + " could not be read — see log)" : ""));
                    } else if (location.kind() == Location.Kind.NETWORK) {
                        updateNetworkStatus();
                    } else if (folderCount == 0 && location.kind() == Location.Kind.SHARES) {
                        statusLabel.setText("Nothing listed yet. Click the address bar and type a server or share, "
                                + "e.g. \\\\SERVER_NAME or \\\\SERVER_NAME\\Books — it will be remembered here.");
                    } else {
                        statusLabel.setText(folderCount + " item(s) in " + location.displayName());
                    }
                } catch (CancellationException e) {
                    // Navigated away; nothing to report.
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Error opening " + location.displayName(), e);
                    statusLabel.setText("Could not open " + location.displayName() + " — see log.");
                    JOptionPane.showMessageDialog(MainWindow.this,
                            "Could not open " + location.displayName() + ":\n" + rootCauseMessage(e),
                            "Open Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        loader.execute();
    }

    // ---------------------------------------------------------------- network discovery

    /** New servers were found (or the search ended): if the Network list is showing, add them to it. */
    private void onDiscoveryChanged() {
        if (currentLocation.kind() != Location.Kind.NETWORK) {
            return;
        }
        java.util.Set<String> shown = new java.util.HashSet<>();
        for (BookRow row : tableModel.getRows()) {
            if (row.isLocation() && row.target.kind() == Location.Kind.SERVER) {
                shown.add(row.target.server().toLowerCase(java.util.Locale.ROOT));
            }
        }
        List<BookRow> added = new ArrayList<>();
        for (String server : NetworkDiscovery.instance().servers()) {
            if (shown.add(server.toLowerCase(java.util.Locale.ROOT))) {
                added.add(BookRow.location(Location.server(server)));
            }
        }
        if (!added.isEmpty()) {
            tableModel.addRows(added);
        }
        updateNetworkStatus();
    }

    private void updateNetworkStatus() {
        long servers = tableModel.getRows().stream()
                .filter(r -> r.isLocation() && r.target.kind() == Location.Kind.SERVER).count();
        if (NetworkDiscovery.instance().isRunning()) {
            statusLabel.setText("Searching the network … " + servers + " server(s) so far. You can keep browsing; "
                    + "the search carries on in the background.");
        } else if (servers == 0) {
            statusLabel.setText("No servers found. Click the address bar and type a server or share, "
                    + "e.g. \\\\SERVER_NAME or \\\\SERVER_NAME\\Books — it will be remembered here. (F5 searches again.)");
        } else {
            statusLabel.setText(servers + " server(s) on the network. (F5 searches again.)");
        }
    }

    /** F5 on the Network list: search again (never starts a second search while one is running). */
    private void refreshNetwork() {
        if (currentLocation.kind() != Location.Kind.NETWORK) {
            return;
        }
        NetworkDiscovery.instance().start(true);
        updateNetworkStatus();
    }

    // ---------------------------------------------------------------- file list

    private void configureTableNavigation() {
        // Double-click or Enter opens a folder, like Explorer.
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
                    int viewRow = table.rowAtPoint(e.getPoint());
                    if (viewRow >= 0) {
                        openRow(viewRow);
                    }
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                showRowMenu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                showRowMenu(e);
            }
        });

        InputMap keys = table.getInputMap(JComponent.WHEN_FOCUSED);
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openFolder");
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "goUp");
        table.getActionMap().put("openFolder", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                int viewRow = table.getSelectedRow();
                if (viewRow >= 0) {
                    openRow(viewRow);
                }
            }
        });
        table.getActionMap().put("goUp", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                goUp();
            }
        });
    }

    private void openRow(int viewRow) {
        BookRow row = tableModel.getRow(table.convertRowIndexToModel(viewRow));
        if (row.isLocation()) {
            navigateTo(row.target);
        }
    }

    /** Right-click on a saved network share offers to forget it. */
    private void showRowMenu(MouseEvent e) {
        if (!e.isPopupTrigger() || currentLocation.kind() != Location.Kind.SHARES) {
            return;
        }
        int viewRow = table.rowAtPoint(e.getPoint());
        if (viewRow < 0) {
            return;
        }
        table.setRowSelectionInterval(viewRow, viewRow);
        BookRow row = tableModel.getRow(table.convertRowIndexToModel(viewRow));
        if (!row.isLocation()) {
            return;
        }
        JPopupMenu menu = new JPopupMenu();
        JMenuItem forget = new JMenuItem("Remove from Network shares");
        forget.addActionListener(ev -> {
            LocationService.forgetShare(row.target.displayName());
            load(currentLocation);
        });
        menu.add(forget);
        menu.show(table, e.getX(), e.getY());
    }

    /** Colours the file name and the proposed name green when the file already follows the pattern, amber if not. */
    private void colorByNaming(javax.swing.JLabel label, JTable t, boolean selected, int viewRow) {
        BookRow book = tableModel.getRows().get(t.convertRowIndexToModel(viewRow));
        if (book.isLocation() || book.metadata == null || book.newBaseName == null) {
            // The renderer is shared between rows and remembers its colour, so put the default back.
            if (!selected) {
                label.setForeground(t.getForeground());
            }
            label.setToolTipText(null);
            return;
        }
        boolean matches = book.matchesNaming();
        label.setForeground(matches ? Theme.MATCH : Theme.MISMATCH);
        label.setToolTipText(matches ? "Already named according to the rename pattern"
                : "Would be renamed to: " + book.newBaseName + book.extension());
    }

    /** The "New Name" column, coloured like the file name. */
    private final class NamingCellRenderer extends javax.swing.table.DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected,
                                                       boolean focus, int row, int column) {
            super.getTableCellRendererComponent(t, value, selected, focus, row, column);
            colorByNaming(this, t, selected, row);
            return this;
        }
    }

    /** Draws a folder/drive/computer icon beside location rows. */
    private final class NameCellRenderer extends javax.swing.table.DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected,
                                                       boolean focus, int row, int column) {
            super.getTableCellRendererComponent(t, value, selected, focus, row, column);
            setIcon(value instanceof BookTableModel.Cell cell && cell.location() != null
                    ? iconFor(cell.location()) : null);
            colorByNaming(this, t, selected, row);
            return this;
        }

        private Icon iconFor(Location location) {
            String key = switch (location.kind()) {
                case FOLDER -> location.path().getParent() == null ? "FileView.hardDriveIcon" : "FileView.directoryIcon";
                default -> "FileView.computerIcon";
            };
            return UIManager.getIcon(key);
        }
    }

    private void updatePreviews() {
        String template = templateField.getText();
        for (BookRow row : tableModel.getRows()) {
            if (row.metadata != null) {
                row.newBaseName = RenameTemplate.render(template, row.metadata);
            }
        }
        tableModel.refreshPreviews();
        onSelectionChanged();
    }

    private void onSelectionChanged() {
        int[] selected = table.getSelectedRows();
        if (selected.length == 1) {
            BookRow row = tableModel.getRow(table.convertRowIndexToModel(selected[0]));
            details.showRow(row, 1);
            details.setProposedName(proposedFileName(row));
        } else {
            List<BookRow> books = new ArrayList<>();
            for (int viewRow : selected) {
                BookRow row = tableModel.getRow(table.convertRowIndexToModel(viewRow));
                if (!row.isLocation() && row.metadata != null) {
                    books.add(row);
                }
            }
            if (books.size() >= 2) {
                details.showBooks(books, selected.length);
            } else {
                details.showRow(null, selected.length);
            }
            details.setProposedName(null);
        }
    }

    /** The full file name (with extension) a book would be renamed to, or null if it has none. */
    private static String proposedFileName(BookRow row) {
        if (row.isLocation() || row.metadata == null || row.newBaseName == null) {
            return null;
        }
        return row.newBaseName + row.extension();
    }

    // ----------------------------------------------------------- metadata edits

    /** A field in the details panel changed: the row already holds the new metadata. */
    private void onMetadataEdited(BookRow row) {
        row.newBaseName = RenameTemplate.render(templateField.getText(), row.metadata);
        tableModel.rowChanged(row);
        details.setProposedName(proposedFileName(row));
    }

    private void onRevert(List<BookRow> rows) {
        for (BookRow row : rows) {
            if (row.isEdited() && row.saving == null) {
                row.metadata = row.original;
                row.newBaseName = RenameTemplate.render(templateField.getText(), row.metadata);
                tableModel.rowChanged(row);
            }
        }
        details.reload();
        refreshProposedName();
    }

    private void refreshProposedName() {
        BookRow shown = details.currentRow();
        details.setProposedName(shown == null ? null : proposedFileName(shown));
    }

    /**
     * Multi-edit: sets the changed fields on every row. Where the books currently hold different
     * values for a field, asks before overwriting them.
     */
    private void onBulkApply(List<BookRow> rows, Map<BulkEdit, Object> changes) {
        StringBuilder warning = new StringBuilder();
        for (BulkEdit field : changes.keySet()) {
            Map<String, Integer> existing = new LinkedHashMap<>();
            for (BookRow row : rows) {
                Object value = field.get(row.metadata);
                if (!BulkEdit.isEmpty(value)) {
                    existing.merge(BulkEdit.display(value), 1, Integer::sum);
                }
            }
            if (existing.size() > 1) {
                warning.append("\n").append(field.label()).append(" — the books currently have ")
                        .append(existing.size()).append(" different values:\n");
                int shown = 0;
                for (Map.Entry<String, Integer> e : existing.entrySet()) {
                    if (shown++ == 6) {
                        warning.append("      … and ").append(existing.size() - 6).append(" more\n");
                        break;
                    }
                    warning.append("      ").append(e.getKey()).append("  (").append(e.getValue()).append(")\n");
                }
                warning.append("   will all become: ").append(BulkEdit.display(changes.get(field))).append("\n");
            }
        }
        if (warning.length() > 0) {
            JTextArea text = new JTextArea("This overwrites different values across " + rows.size() + " books:\n"
                    + warning + "\nContinue?");
            text.setEditable(false);
            text.setOpaque(false);
            text.setLineWrap(true);
            text.setWrapStyleWord(true);
            JScrollPane pane = new JScrollPane(text);
            pane.setPreferredSize(new Dimension(520, 260));
            pane.setBorder(null);
            if (JOptionPane.showConfirmDialog(this, pane, "Overwrite Different Values",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
                return;
            }
        }
        for (BookRow row : rows) {
            BookMetadata updated = row.metadata;
            for (Map.Entry<BulkEdit, Object> change : changes.entrySet()) {
                updated = change.getKey().with(updated, change.getValue());
            }
            row.metadata = updated;
            row.newBaseName = RenameTemplate.render(templateField.getText(), updated);
            tableModel.rowChanged(row);
        }
        details.reload();
        statusLabel.setText("Updated " + String.join(", ", changes.keySet().stream().map(BulkEdit::label).toList())
                + " on " + rows.size() + " books (not saved to the EPUBs yet).");
    }

    /** Saves run one at a time, in the background, so the window never waits on a slow disk or share. */
    private final java.util.concurrent.ExecutorService saveExecutor = java.util.concurrent.Executors
            .newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "folio-save");
                t.setDaemon(true);
                return t;
            });
    private int pendingSaves;

    /**
     * Queues the edited books for writing to their EPUBs and returns immediately. The panel stays
     * usable: edits made while a save runs are kept and can be saved again afterwards.
     */
    private void onSave(List<BookRow> rows) {
        for (BookRow row : rows) {
            if (!row.needsSave()) {
                continue;
            }
            final Path file = row.file;
            final BookMetadata edited = row.metadata;
            row.saving = edited;
            pendingSaves++;
            details.refreshButtons();
            statusLabel.setText("Saving " + pendingSaves + " book" + (pendingSaves == 1 ? "" : "s") + " …");

            saveExecutor.execute(() -> {
                BookMetadata readBack = null;
                Exception failure = null;
                try {
                    EpubMetadataWriter.write(file, edited);
                    readBack = EpubMetadataReader.read(file);
                } catch (Exception e) {
                    failure = e;
                }
                final BookMetadata stored = readBack;
                final Exception error = failure;
                SwingUtilities.invokeLater(() -> saveFinished(row, file, edited, stored, error));
            });
        }
    }

    private void saveFinished(BookRow row, Path file, BookMetadata written, BookMetadata stored, Exception error) {
        pendingSaves--;
        row.saving = null;
        if (error != null) {
            LOG.log(Level.WARNING, "Could not save metadata to " + file, error);
            statusLabel.setText("Could not save metadata to " + file.getFileName() + " — see log.");
            details.refreshButtons();
            JOptionPane.showMessageDialog(this,
                    "Could not save metadata to " + file.getFileName() + ":\n" + rootCauseMessage(error),
                    "Save Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        boolean differs = !stored.equals(written);
        if (differs) {
            LOG.warning(() -> "After saving " + file.getFileName()
                    + " the file reads back differently than what was entered: " + stored);
        }
        boolean untouched = row.metadata.equals(written);
        row.markStored(written, stored);
        row.newBaseName = RenameTemplate.render(templateField.getText(), row.metadata);
        tableModel.rowChanged(row);
        // Only refill the form if the file's values differ from what is on screen; otherwise leave the
        // fields (and the user's cursor) alone.
        boolean shown = details.currentRow() == row || details.currentRows().contains(row);
        if (shown) {
            details.afterSave(untouched && differs);
            refreshProposedName();
        }
        statusLabel.setText(pendingSaves > 0
                ? "Saving " + pendingSaves + " book" + (pendingSaves == 1 ? "" : "s") + " …"
                : "Saved metadata to " + file.getFileName() + " (original kept as "
                + EpubMetadataWriter.backupPath(file).getFileName() + ")"
                + (differs ? " — the file reads back slightly differently, see log." : "."));
    }

    /** True if there is nothing unsaved, or the user agrees to throw their edits away. */
    private boolean confirmDiscardEdits() {
        long edited = tableModel.getRows().stream().filter(BookRow::isEdited).count();
        if (edited == 0) {
            return true;
        }
        return JOptionPane.showConfirmDialog(this,
                edited + " book(s) have metadata edits that haven't been saved to the EPUB.\n"
                        + "Discard them?",
                "Unsaved Edits", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    // ------------------------------------------------------------------- rename

    private record RenamePlan(BookRow row, Path target) {
    }

    private void onRename() {
        if (pendingSaves > 0) {
            JOptionPane.showMessageDialog(this, "Metadata is still being saved to the EPUBs. Try again in a moment.",
                    "Rename", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        List<BookRow> targets = selectedOrAllRows();
        List<RenamePlan> plans = targets.stream()
                .filter(r -> r.metadata != null && r.newBaseName != null && !r.newBaseName.isBlank())
                .map(r -> new RenamePlan(r, r.file.resolveSibling(r.newBaseName + r.extension())))
                .filter(p -> !p.target().equals(p.row().file))
                .collect(Collectors.toList());

        if (plans.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Nothing to rename.", "Rename", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(this, buildRenameConfirmation(plans),
                "Confirm Rename", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) {
            LOG.fine("Rename cancelled by user.");
            return;
        }

        int renamed = 0;
        List<String> failures = new ArrayList<>();
        for (RenamePlan plan : plans) {
            BookRow row = plan.row();
            Path target = plan.target();
            if (Files.exists(target)) {
                LOG.warning(() -> "Skipped rename, target already exists: " + target);
                failures.add(row.file.getFileName() + " — a file with the new name already exists");
                continue;
            }
            try {
                Files.move(row.file, target);
                LOG.info(() -> "Renamed " + row.file.getFileName() + " -> " + target.getFileName());
                row.file = target;
                renamed++;
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Could not rename " + row.file + " to " + target, e);
                failures.add(row.file.getFileName() + " — " + e.getMessage());
            }
        }

        tableModel.refreshPreviews();
        statusLabel.setText("Renamed " + renamed + " file(s)."
                + (failures.isEmpty() ? "" : " " + failures.size() + " could not be renamed — see log."));

        if (!failures.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Some files could not be renamed:\n" + String.join("\n", failures),
                    "Rename Issues", JOptionPane.WARNING_MESSAGE);
        }
    }

    private List<BookRow> selectedOrAllRows() {
        int[] selectedViewRows = table.getSelectedRows();
        if (selectedViewRows.length == 0) {
            return new ArrayList<>(tableModel.getRows());
        }
        List<BookRow> rows = new ArrayList<>();
        for (int viewRow : selectedViewRows) {
            rows.add(tableModel.getRow(table.convertRowIndexToModel(viewRow)));
        }
        return rows;
    }

    /** The confirmation dialog's content: every old name and the new name it will get. */
    private static JComponent buildRenameConfirmation(List<RenamePlan> plans) {
        StringBuilder text = new StringBuilder();
        for (RenamePlan plan : plans) {
            if (text.length() > 0) {
                text.append("\n\n");
            }
            text.append(plan.row().file.getFileName()).append("\n  → ").append(plan.target().getFileName());
        }
        JTextArea list = new JTextArea(text.toString(), Math.min(plans.size() * 3, 14), 60);
        list.setEditable(false);
        list.setLineWrap(true);
        list.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(list, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        long unsaved = plans.stream().filter(p -> p.row().isEdited()).count();
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        JLabel heading = new JLabel("Rename " + plans.size() + " file(s) using the current pattern?");
        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        north.add(heading);
        if (unsaved > 0) {
            north.add(new JLabel(unsaved + " of these use edited metadata that hasn't been saved to the EPUB."));
        }
        panel.add(north, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    private static String rootCauseMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.toString();
    }

    @FunctionalInterface
    private interface SimpleDocumentListener extends DocumentListener {
        void update(DocumentEvent e);

        @Override
        default void insertUpdate(DocumentEvent e) {
            update(e);
        }

        @Override
        default void removeUpdate(DocumentEvent e) {
            update(e);
        }

        @Override
        default void changedUpdate(DocumentEvent e) {
            update(e);
        }
    }
}
