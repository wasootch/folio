package ca.scottadams.folio.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * An Explorer-style address bar. Normally it shows the current location as
 * clickable breadcrumbs; each segment jumps to that level, and the {@code ›}
 * after a segment drops down that level's sub-locations. Clicking empty space
 * (or {@link #showEditor()}) switches to a plain text field so a path can be
 * typed or pasted.
 */
final class BreadcrumbBar extends JPanel {

    /** Receives the user's navigation requests. */
    interface Listener {
        void navigate(Location location);

        void pathTyped(String text);
    }

    private static final String CARD_CRUMBS = "crumbs";
    private static final String CARD_EDITOR = "editor";

    private final Listener listener;
    private final CardLayout cards = new CardLayout();
    private final CrumbPanel crumbs = new CrumbPanel();
    private final JScrollPane crumbScroll = new JScrollPane(crumbs,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    private final JTextField editor = new JTextField();
    private Location current = Location.HOME;

    BreadcrumbBar(Listener listener) {
        this.listener = listener;
        setLayout(cards);

        int height = Math.max(editor.getPreferredSize().height, 26);

        JPanel crumbHolder = new JPanel(new BorderLayout());
        crumbHolder.setBackground(UIManager.getColor("TextField.background"));
        crumbHolder.setBorder(UIManager.getBorder("TextField.border"));
        crumbScroll.setBorder(null);
        crumbScroll.setOpaque(false);
        crumbScroll.getViewport().setOpaque(false);
        crumbScroll.setPreferredSize(new Dimension(300, height));
        crumbHolder.add(crumbScroll, BorderLayout.CENTER);

        MouseAdapter blankClick = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    showEditor();
                }
            }
        };
        crumbHolder.addMouseListener(blankClick);
        crumbScroll.getViewport().addMouseListener(blankClick);
        crumbs.addMouseListener(blankClick);
        crumbHolder.setToolTipText("Click empty space (or press Ctrl+L) to type or paste a path");

        editor.addActionListener(e -> {
            String text = editor.getText();
            showCrumbs();
            listener.pathTyped(text);
        });
        editor.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    showCrumbs();
                }
            }
        });
        editor.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                if (!e.isTemporary()) {
                    showCrumbs();
                }
            }
        });

        add(crumbHolder, CARD_CRUMBS);
        add(editor, CARD_EDITOR);
        setMinimumSize(new Dimension(120, height));
        setLocation(Location.HOME);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    /** Shows {@code location} as breadcrumbs. */
    void setLocation(Location location) {
        current = location;
        crumbs.removeAll();
        for (Location step : location.trail()) {
            crumbs.add(crumbButton(step));
            crumbs.add(arrowButton(step));
        }
        showCrumbs();
        crumbs.revalidate();
        crumbs.repaint();
        // Keep the deepest (right-most) segment visible when the path is too long to fit.
        SwingUtilities.invokeLater(() -> {
            JViewport viewport = crumbScroll.getViewport();
            int overflow = crumbs.getPreferredSize().width - viewport.getWidth();
            viewport.setViewPosition(new Point(Math.max(0, overflow), 0));
        });
    }

    /** Switches to the text field, pre-filled with the current path and selected. */
    void showEditor() {
        editor.setText(current.editText());
        cards.show(this, CARD_EDITOR);
        editor.requestFocusInWindow();
        editor.selectAll();
    }

    private void showCrumbs() {
        cards.show(this, CARD_CRUMBS);
    }

    private JComponent crumbButton(Location target) {
        CrumbButton button = new CrumbButton(target.displayName());
        button.addActionListener(e -> listener.navigate(target));
        return button;
    }

    private JComponent arrowButton(Location parent) {
        CrumbButton button = new CrumbButton("›");
        button.setToolTipText("Show locations inside " + parent.displayName());
        button.addActionListener(e -> showChildren(parent, button));
        return button;
    }

    /** Drops down a scrollable list of {@code parent}'s sub-locations, loaded in the background. */
    private void showChildren(Location parent, JComponent invoker) {
        JPopupMenu menu = new JPopupMenu();
        menu.add(messageItem("Loading…"));
        menu.show(invoker, 0, invoker.getHeight());

        new SwingWorker<List<Location>, Void>() {
            @Override
            protected List<Location> doInBackground() throws Exception {
                return LocationService.listChildren(parent);
            }

            @Override
            protected void done() {
                menu.removeAll();
                try {
                    List<Location> children = get();
                    if (children.isEmpty()) {
                        menu.add(messageItem("(empty)"));
                    } else {
                        menu.add(childList(menu, children));
                    }
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    menu.add(messageItem("Unavailable: " + cause.getMessage()));
                }
                menu.pack();
                menu.revalidate();
                menu.repaint();
            }
        }.execute();
    }

    private JComponent childList(JPopupMenu menu, List<Location> children) {
        JList<Location> list = new JList<>(children.toArray(new Location[0]));
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index,
                                                          boolean selected, boolean focus) {
                return super.getListCellRendererComponent(
                        l, ((Location) value).displayName(), index, selected, false);
            }
        });
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        Runnable open = () -> {
            Location chosen = list.getSelectedValue();
            if (chosen != null) {
                menu.setVisible(false);
                listener.navigate(chosen);
            }
        };
        list.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int index = list.locationToIndex(e.getPoint());
                if (index >= 0 && list.getCellBounds(index, index).contains(e.getPoint())) {
                    list.setSelectedIndex(index);
                } else {
                    list.clearSelection();
                }
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent e) {
                int index = list.locationToIndex(e.getPoint());
                if (index >= 0 && list.getCellBounds(index, index).contains(e.getPoint())) {
                    list.setSelectedIndex(index);
                    open.run();
                }
            }
        });
        list.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    open.run();
                }
            }
        });

        JScrollPane scroll = new JScrollPane(list);
        Dimension content = list.getPreferredSize();
        scroll.setPreferredSize(new Dimension(
                Math.max(180, content.width + 28), Math.min(content.height + 4, 320)));
        return scroll;
    }

    private static JMenuItem messageItem(String text) {
        JMenuItem item = new JMenuItem(text);
        item.setEnabled(false);
        return item;
    }

    /** Segments are laid out left to right and scroll horizontally when the path is long. */
    private static final class CrumbPanel extends JPanel implements Scrollable {
        CrumbPanel() {
            setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
            setOpaque(false);
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
            return false;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return true;
        }
    }

    /** A borderless button that only shows a highlight while hovered, like an Explorer breadcrumb. */
    private static final class CrumbButton extends JButton {
        private boolean hover;

        CrumbButton(String text) {
            super(text);
            setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));
            setContentAreaFilled(false);
            setFocusPainted(false);
            setFocusable(false);
            setOpaque(false);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (hover) {
                g.setColor(new Color(128, 128, 128, 70));
                g.fillRoundRect(0, 1, getWidth(), getHeight() - 2, 6, 6);
            }
            super.paintComponent(g);
        }
    }
}
