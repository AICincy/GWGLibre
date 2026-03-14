package com.libreshockwave.editor.docking;

import com.libreshockwave.editor.panel.EditorPanel;

import javax.swing.*;
import javax.swing.plaf.basic.BasicInternalFrameUI;
import java.awt.*;
import java.awt.event.*;
import java.beans.PropertyVetoException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manages IDE-style docking for editor panels.
 * Panels can be docked to LEFT, RIGHT, or BOTTOM zones via drag-to-edge
 * or right-click context menus. Docked panels appear as tabs in resizable
 * split pane zones. Undocked panels float as JInternalFrames in the desktop.
 */
public class DockingManager {

    public enum Zone { LEFT, RIGHT, BOTTOM }

    private final JDesktopPane desktop;
    private final Map<String, EditorPanel> allPanels;

    // Dock zone tab panes
    private final JTabbedPane leftTabs = new JTabbedPane(JTabbedPane.TOP);
    private final JTabbedPane rightTabs = new JTabbedPane(JTabbedPane.TOP);
    private final JTabbedPane bottomTabs = new JTabbedPane(JTabbedPane.TOP);

    // Placeholder panels for collapsed zones (zero-size)
    private final JPanel emptyLeft = newEmptyPanel();
    private final JPanel emptyRight = newEmptyPanel();
    private final JPanel emptyBottom = newEmptyPanel();

    // Split panes: outerSplit(V) = [innerSplit(H) = [left | centerRightSplit(H) = [desktop | right]] | bottom]
    private final JSplitPane outerSplit;
    private final JSplitPane innerSplit;
    private final JSplitPane centerRightSplit;

    // Docked panel state
    private final Map<String, Zone> dockedZones = new LinkedHashMap<>();
    private final Map<String, Rectangle> savedBounds = new LinkedHashMap<>();
    private final Map<String, Container> savedContent = new LinkedHashMap<>();

    // Remembered divider sizes for when zones re-appear
    private int leftDivLoc = 200;
    private int rightDivWidth = 300;
    private int bottomDivHeight = 250;

    // Snap overlay shown during drag
    private final SnapOverlay snapOverlay;
    private Zone pendingSnap;

    private static final int SNAP_MARGIN = 50;
    private static final int DIVIDER_SIZE = Math.max(UIManager.getInt("SplitPane.dividerSize"), 4);

    public DockingManager(JFrame frame, JDesktopPane desktop, Map<String, EditorPanel> allPanels) {
        this.desktop = desktop;
        this.allPanels = allPanels;

        // Minimum sizes for dock zones
        leftTabs.setMinimumSize(new Dimension(100, 0));
        rightTabs.setMinimumSize(new Dimension(100, 0));
        bottomTabs.setMinimumSize(new Dimension(0, 60));

        // Snap overlay (lives on desktop's drag layer)
        snapOverlay = new SnapOverlay();
        snapOverlay.setVisible(false);
        desktop.add(snapOverlay, JLayeredPane.DRAG_LAYER);
        desktop.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                snapOverlay.setBounds(0, 0, desktop.getWidth(), desktop.getHeight());
            }
        });

        // Tab right-click menus
        installTabContextMenu(leftTabs, Zone.LEFT);
        installTabContextMenu(rightTabs, Zone.RIGHT);
        installTabContextMenu(bottomTabs, Zone.BOTTOM);

        // Build split pane hierarchy
        centerRightSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, desktop, emptyRight);
        centerRightSplit.setContinuousLayout(true);
        centerRightSplit.setResizeWeight(1.0);
        centerRightSplit.setDividerSize(0);

        innerSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, emptyLeft, centerRightSplit);
        innerSplit.setContinuousLayout(true);
        innerSplit.setResizeWeight(0.0);
        innerSplit.setDividerSize(0);
        innerSplit.setDividerLocation(0);

        outerSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, innerSplit, emptyBottom);
        outerSplit.setContinuousLayout(true);
        outerSplit.setResizeWeight(1.0);
        outerSplit.setDividerSize(0);

        // Custom desktop manager for drag-to-dock
        desktop.setDesktopManager(new DockingDesktopManager());

        // Title bar right-click menus on all panels
        for (EditorPanel panel : allPanels.values()) {
            installTitleBarMenu(panel);
        }
    }

    /** Returns the root component to add to the frame's content area. */
    public JComponent getComponent() {
        return outerSplit;
    }

    // ---- Public API ----

    public void dock(String title, Zone zone) {
        EditorPanel panel = allPanels.get(title);
        if (panel == null || dockedZones.containsKey(title)) return;

        // Save state for later restore
        savedBounds.put(title, panel.getBounds());
        Container content = panel.getContentPane();
        savedContent.put(title, content);

        // Detach content from JInternalFrame
        panel.setContentPane(new JPanel());
        panel.setVisible(false);

        // Add to dock zone
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(content, BorderLayout.CENTER);
        JTabbedPane tabs = tabsFor(zone);
        tabs.addTab(title, wrapper);
        tabs.setTabComponentAt(tabs.getTabCount() - 1, createTabLabel(title, tabs));
        tabs.setSelectedIndex(tabs.getTabCount() - 1);

        dockedZones.put(title, zone);
        updateDockLayout();
    }

    /**
     * Undock a panel, restoring its content to the JInternalFrame.
     * Does NOT automatically make the floating frame visible.
     */
    public void undock(String title) {
        if (!dockedZones.containsKey(title)) return;

        Zone zone = dockedZones.remove(title);
        JTabbedPane tabs = tabsFor(zone);

        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (title.equals(tabs.getTitleAt(i))) {
                Container content = savedContent.remove(title);
                if (content != null) {
                    EditorPanel panel = allPanels.get(title);
                    panel.setContentPane(content);
                    Rectangle bounds = savedBounds.remove(title);
                    if (bounds != null) panel.setBounds(bounds);
                }
                tabs.removeTabAt(i);
                break;
            }
        }

        updateDockLayout();
    }

    /** Undock and show the panel as a floating window. */
    public void undockAndShow(String title) {
        undock(title);
        EditorPanel panel = allPanels.get(title);
        if (panel != null) {
            panel.setVisible(true);
            try { panel.setSelected(true); } catch (PropertyVetoException ignored) {}
        }
    }

    public boolean isDocked(String title) {
        return dockedZones.containsKey(title);
    }

    /** Toggle visibility, handling both docked and floating states. */
    public void togglePanel(String title, boolean visible) {
        if (dockedZones.containsKey(title)) {
            if (visible) {
                // Select the tab
                Zone zone = dockedZones.get(title);
                JTabbedPane tabs = tabsFor(zone);
                for (int i = 0; i < tabs.getTabCount(); i++) {
                    if (title.equals(tabs.getTitleAt(i))) {
                        tabs.setSelectedIndex(i);
                        break;
                    }
                }
            } else {
                undock(title);
                EditorPanel panel = allPanels.get(title);
                if (panel != null) panel.setVisible(false);
            }
        } else {
            EditorPanel panel = allPanels.get(title);
            if (panel != null) {
                panel.setVisible(visible);
                if (visible) {
                    try { panel.setSelected(true); } catch (PropertyVetoException ignored) {}
                }
            }
        }
    }

    /** Undock all panels and restore them to floating. */
    public void undockAll() {
        for (String title : dockedZones.keySet().toArray(new String[0])) {
            undockAndShow(title);
        }
    }

    /** Set up a default IDE-style docked layout. */
    public void applyDefaultDockedLayout() {
        undockAll();

        dock("Tool Palette", Zone.LEFT);
        dock("Property Inspector", Zone.RIGHT);
        dock("Bytecode Debugger", Zone.RIGHT);
        dock("Score", Zone.BOTTOM);
        dock("Cast", Zone.BOTTOM);
        dock("Script", Zone.BOTTOM);
        dock("Message", Zone.BOTTOM);

        // Show the Stage floating in center
        EditorPanel stage = allPanels.get("Stage");
        if (stage != null) {
            stage.setVisible(true);
            stage.setBounds(10, 10, 660, 500);
            try { stage.setSelected(true); } catch (PropertyVetoException ignored) {}
        }
    }

    // ---- Layout updates ----

    private void updateDockLayout() {
        SwingUtilities.invokeLater(() -> {
            updateZone(leftTabs, innerSplit, true, emptyLeft);
            updateZone(rightTabs, centerRightSplit, false, emptyRight);
            updateZoneVertical(bottomTabs, outerSplit, emptyBottom);
            outerSplit.revalidate();
            outerSplit.repaint();
        });
    }

    private void updateZone(JTabbedPane tabs, JSplitPane split, boolean isLeft, JPanel empty) {
        if (tabs.getTabCount() > 0) {
            if (isLeft) {
                if (split.getLeftComponent() != tabs) split.setLeftComponent(tabs);
                split.setDividerSize(DIVIDER_SIZE);
                SwingUtilities.invokeLater(() -> split.setDividerLocation(leftDivLoc));
            } else {
                if (split.getRightComponent() != tabs) split.setRightComponent(tabs);
                split.setDividerSize(DIVIDER_SIZE);
                SwingUtilities.invokeLater(() -> {
                    int w = split.getWidth();
                    if (w > 0) split.setDividerLocation(w - rightDivWidth);
                });
            }
        } else {
            // Save current divider location before collapsing
            if (isLeft) {
                if (split.getLeftComponent() == tabs && split.getDividerLocation() > 50) {
                    leftDivLoc = split.getDividerLocation();
                }
                split.setLeftComponent(empty);
                split.setDividerSize(0);
                split.setDividerLocation(0);
            } else {
                if (split.getRightComponent() == tabs) {
                    int w = split.getWidth();
                    int d = split.getDividerLocation();
                    if (w > 0 && d > 0 && w - d > 50) rightDivWidth = w - d;
                }
                split.setRightComponent(empty);
                split.setDividerSize(0);
                SwingUtilities.invokeLater(() -> split.setDividerLocation(split.getWidth()));
            }
        }
    }

    private void updateZoneVertical(JTabbedPane tabs, JSplitPane split, JPanel empty) {
        if (tabs.getTabCount() > 0) {
            if (split.getBottomComponent() != tabs) split.setBottomComponent(tabs);
            split.setDividerSize(DIVIDER_SIZE);
            SwingUtilities.invokeLater(() -> {
                int h = split.getHeight();
                if (h > 0) split.setDividerLocation(h - bottomDivHeight);
            });
        } else {
            if (split.getBottomComponent() == tabs) {
                int h = split.getHeight();
                int d = split.getDividerLocation();
                if (h > 0 && d > 0 && h - d > 50) bottomDivHeight = h - d;
            }
            split.setBottomComponent(empty);
            split.setDividerSize(0);
            SwingUtilities.invokeLater(() -> split.setDividerLocation(split.getHeight()));
        }
    }

    // ---- Helpers ----

    private JTabbedPane tabsFor(Zone zone) {
        return switch (zone) {
            case LEFT -> leftTabs;
            case RIGHT -> rightTabs;
            case BOTTOM -> bottomTabs;
        };
    }

    private static JPanel newEmptyPanel() {
        return new JPanel() {
            @Override public Dimension getMinimumSize() { return new Dimension(0, 0); }
            @Override public Dimension getPreferredSize() { return new Dimension(0, 0); }
        };
    }

    private JPanel createTabLabel(String title, JTabbedPane parent) {
        JPanel tab = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        tab.setOpaque(false);
        tab.add(new JLabel(title));

        JButton closeBtn = new JButton("\u00d7");
        closeBtn.setMargin(new Insets(0, 2, 0, 2));
        closeBtn.setFont(closeBtn.getFont().deriveFont(11f));
        closeBtn.setContentAreaFilled(false);
        closeBtn.setBorderPainted(false);
        closeBtn.setFocusable(false);
        closeBtn.setToolTipText("Float");
        closeBtn.addActionListener(e -> undockAndShow(title));
        tab.add(closeBtn);

        return tab;
    }

    // ---- Context menus ----

    private void installTabContextMenu(JTabbedPane tabs, Zone zone) {
        tabs.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { popup(e); }
            @Override public void mouseReleased(MouseEvent e) { popup(e); }

            private void popup(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int idx = tabs.indexAtLocation(e.getX(), e.getY());
                if (idx < 0) return;
                String title = tabs.getTitleAt(idx);

                JPopupMenu menu = new JPopupMenu();

                JMenuItem floatItem = new JMenuItem("Float");
                floatItem.addActionListener(ev -> undockAndShow(title));
                menu.add(floatItem);

                menu.addSeparator();

                for (Zone z : Zone.values()) {
                    if (z == zone) continue;
                    String label = "Move to " + zoneName(z);
                    JMenuItem moveItem = new JMenuItem(label);
                    moveItem.addActionListener(ev -> {
                        undock(title);
                        dock(title, z);
                    });
                    menu.add(moveItem);
                }

                menu.show(tabs, e.getX(), e.getY());
            }
        });
    }

    private void installTitleBarMenu(EditorPanel panel) {
        try {
            JComponent titleBar = ((BasicInternalFrameUI) panel.getUI()).getNorthPane();
            if (titleBar == null) return;

            titleBar.addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { popup(e); }
                @Override public void mouseReleased(MouseEvent e) { popup(e); }

                private void popup(MouseEvent e) {
                    if (!e.isPopupTrigger()) return;
                    JPopupMenu menu = new JPopupMenu();
                    for (Zone z : Zone.values()) {
                        JMenuItem item = new JMenuItem("Dock " + zoneName(z));
                        item.addActionListener(ev -> dock(panel.getTitle(), z));
                        menu.add(item);
                    }
                    menu.show(titleBar, e.getX(), e.getY());
                }
            });
        } catch (ClassCastException ignored) {
            // Non-basic L&F — skip title bar menu
        }
    }

    private static String zoneName(Zone z) {
        return z.name().charAt(0) + z.name().substring(1).toLowerCase();
    }

    // ---- Snap overlay (shown during drag-to-dock) ----

    private class SnapOverlay extends JComponent {
        private Zone zone;

        void setZone(Zone z) {
            this.zone = z;
            setVisible(z != null);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (zone == null) return;
            Graphics2D g2 = (Graphics2D) g.create();
            int w = getWidth(), h = getHeight();

            Rectangle r = switch (zone) {
                case LEFT -> new Rectangle(0, 0, w / 4, h);
                case RIGHT -> new Rectangle(w - w / 4, 0, w / 4, h);
                case BOTTOM -> new Rectangle(0, h - h / 3, w, h / 3);
            };

            g2.setColor(new Color(0, 120, 215, 50));
            g2.fillRect(r.x, r.y, r.width, r.height);
            g2.setColor(new Color(0, 120, 215, 160));
            g2.setStroke(new BasicStroke(2));
            g2.drawRect(r.x + 1, r.y + 1, r.width - 3, r.height - 3);
            g2.dispose();
        }
    }

    // ---- Custom desktop manager for drag-to-dock ----

    private class DockingDesktopManager extends DefaultDesktopManager {

        @Override
        public void beginDraggingFrame(JComponent f) {
            super.beginDraggingFrame(f);
            pendingSnap = null;
        }

        @Override
        public void dragFrame(JComponent f, int newX, int newY) {
            super.dragFrame(f, newX, newY);

            int dw = desktop.getWidth();
            int dh = desktop.getHeight();
            int fw = f.getWidth();
            int fh = f.getHeight();

            Zone snap = null;
            if (newX < SNAP_MARGIN) {
                snap = Zone.LEFT;
            } else if (newX + fw > dw - SNAP_MARGIN) {
                snap = Zone.RIGHT;
            } else if (newY + fh > dh - SNAP_MARGIN) {
                snap = Zone.BOTTOM;
            }

            if (snap != pendingSnap) {
                pendingSnap = snap;
                snapOverlay.setZone(snap);
            }
        }

        @Override
        public void endDraggingFrame(JComponent f) {
            super.endDraggingFrame(f);

            if (pendingSnap != null && f instanceof EditorPanel panel) {
                Zone zone = pendingSnap;
                pendingSnap = null;
                snapOverlay.setZone(null);
                SwingUtilities.invokeLater(() -> dock(panel.getTitle(), zone));
            } else {
                pendingSnap = null;
                snapOverlay.setZone(null);
            }
        }
    }
}
