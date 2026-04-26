package zxj81;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ZXJ81 {
    private final Path baseDir;
    private final ZX81Machine machine;
    private final ZX81Keyboard keyboard;
    private final ZX81Screen screen;
    private final JFrame frame;
    private final JLabel status;
    private Timer timer;

    private ZXJ81(Path baseDir) throws IOException {
        this.baseDir = baseDir;
        this.machine = new ZX81Machine(baseDir);
        this.keyboard = new ZX81Keyboard(machine.bus());
        this.screen = new ZX81Screen(machine.bus(), machine.rom(), baseDir);
        this.frame = new JFrame("ZXJ81 - Java ZX81");
        this.status = new JLabel("F1 help | F2 keyboard | F5 tapes | F8 reset | F12 status");
    }
    
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                Path baseDir = Path.of("").toAbsolutePath().normalize();
                ZXJ81 app = new ZXJ81(baseDir);
                app.show(args);
            } catch (Exception ex) {
                ex.printStackTrace();
                JOptionPane.showMessageDialog(null, ex.getMessage(), "ZXJ81", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    private void show(String[] args) {
        configureFrame();
        if (args.length > 0 && !args[0].isBlank()) {
            Path tape = Path.of(args[0]);
            if (!tape.isAbsolute()) {
                tape = baseDir.resolve("tapes").resolve(args[0]);
            }
            loadTape(tape, true);
        }
        frame.setVisible(true);
        screen.requestFocusInWindow();
        startEmulation();
    }

    private void configureFrame() {
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.setJMenuBar(createMenuBar());
        frame.add(screen, BorderLayout.CENTER);

        status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        status.setOpaque(true);
        status.setBackground(new Color(22, 24, 27));
        status.setForeground(new Color(239, 233, 220));
        frame.add(status, BorderLayout.SOUTH);

        KeyAdapter adapter = new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                handleKeyPressed(event);
            }

            @Override
            public void keyReleased(KeyEvent event) {
                handleKeyReleased(event);
            }
        };
        frame.addKeyListener(adapter);
        screen.addKeyListener(adapter);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setMinimumSize(new Dimension(420, 340));
    }

    private JMenuBar createMenuBar() {
        JMenuBar bar = new JMenuBar();
        JMenu machineMenu = new JMenu("Machine");
        JMenu tapeMenu = new JMenu("Tapes");
        JMenu helpMenu = new JMenu("Help");

        JMenuItem reset = new JMenuItem("Hard reset (F8)");
        reset.addActionListener(e -> hardReset());
        machineMenu.add(reset);

        JMenuItem toggleStatus = new JMenuItem("Show/hide status (F12)");
        toggleStatus.addActionListener(e -> toggleStatusBar());
        machineMenu.add(toggleStatus);

        JMenuItem exit = new JMenuItem("Exit");
        exit.addActionListener(e -> frame.dispose());
        machineMenu.add(exit);

        JMenuItem launcher = new JMenuItem("Tape selector (F5)");
        launcher.addActionListener(e -> openTapeLauncher());
        tapeMenu.add(launcher);

        JMenuItem help = new JMenuItem("Help (F1)");
        help.addActionListener(e -> showHelp());
        helpMenu.add(help);

        bar.add(machineMenu);
        bar.add(tapeMenu);
        bar.add(helpMenu);
        return bar;
    }

    private void startEmulation() {
        timer = new Timer(20, event -> {
            machine.runFrame();
            screen.refreshFrame();
            updateStatus();
        });
        timer.start();
    }

    private void updateStatus() {
        status.setText(String.format(
            "PC %04X  SP %04X  T %d  Tape: %s",
            machine.cpu().getRegPC(),
            machine.cpu().getRegSP(),
            machine.bus().getTstates(),
            machine.tape().currentFilename().isBlank() ? "-" : machine.tape().currentFilename()));
    }

    private void handleKeyPressed(KeyEvent event) {
        switch (event.getKeyCode()) {
            case KeyEvent.VK_F1 -> {
                screen.setHelpOverlay(true);
                event.consume();
            }
            case KeyEvent.VK_F2 -> {
                screen.setKeyboardOverlay(true);
                event.consume();
            }
            case KeyEvent.VK_F5 -> {
                openTapeLauncher();
                event.consume();
            }
            case KeyEvent.VK_F8 -> {
                hardReset();
                event.consume();
            }
            case KeyEvent.VK_F12 -> {
                toggleStatusBar();
                event.consume();
            }
            default -> {
                if (keyboard.keyPressed(event)) {
                    event.consume();
                }
            }
        }
    }

    private void handleKeyReleased(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.VK_F1) {
            screen.setHelpOverlay(false);
            event.consume();
            return;
        }
        if (event.getKeyCode() == KeyEvent.VK_F2) {
            screen.setKeyboardOverlay(false);
            event.consume();
            return;
        }
        if (keyboard.keyReleased(event)) {
            event.consume();
        }
    }

    private void hardReset() {
        keyboard.clear();
        machine.hardReset();
        screen.refreshFrame();
        status.setText("Hard reset");
        screen.requestFocusInWindow();
    }

    private void toggleStatusBar() {
        status.setVisible(!status.isVisible());
        frame.revalidate();
        frame.repaint();
        screen.requestFocusInWindow();
    }

    private void loadTape(Path tape, boolean autoRun) {
        if (!Files.exists(tape)) {
            JOptionPane.showMessageDialog(frame, "Tape not found:\n" + tape, "ZXJ81", JOptionPane.WARNING_MESSAGE);
            return;
        }
        boolean ok = machine.loadTape(tape, autoRun);
        if (!ok) {
            JOptionPane.showMessageDialog(frame, "Could not load:\n" + tape, "ZXJ81", JOptionPane.ERROR_MESSAGE);
        } else {
            status.setText("Tape loaded: " + tape.getFileName());
        }
        screen.requestFocusInWindow();
    }

    private void openTapeLauncher() {
        List<Path> tapes = machine.tape().listTapes();
        if (tapes.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "No .P files found in " + baseDir.resolve("tapes"), "ZXJ81", JOptionPane.INFORMATION_MESSAGE);
            screen.requestFocusInWindow();
            return;
        }

        JDialog dialog = new JDialog(frame, "ZXJ81 tapes", true);
        DefaultListModel<Path> model = new DefaultListModel<>();
        tapes.forEach(model::addElement);
        JList<Path> list = new JList<>(model);
        list.setVisibleRowCount(Math.min(10, tapes.size()));
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
                Path path = (Path) value;
                long size = 0;
                try {
                    size = Files.size(path);
                } catch (IOException ignored) {
                }
                label.setText(String.format("%s  (%d bytes)", path.getFileName(), size));
                return label;
            }
        });
        list.setSelectedIndex(0);
        list.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));

        JButton load = new JButton("Load");
        JButton cancel = new JButton("Cancel");
        load.addActionListener(event -> {
            Path selected = list.getSelectedValue();
            dialog.dispose();
            if (selected != null) {
                loadTape(selected, true);
            }
        });
        cancel.addActionListener(event -> dialog.dispose());

        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent event) {
                if (event.getClickCount() == 2) {
                    load.doClick();
                }
            }
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancel);
        buttons.add(load);

        dialog.add(new JScrollPane(list), BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.setSize(420, 300);
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
        screen.requestFocusInWindow();
    }

    private void showHelp() {
        JOptionPane.showMessageDialog(frame,
            """
            ZXJ81

            F1  Help
            F2  Show the keyboard photo/guide while held
            F5  .P tape selector
            F8  Hard reset
            F12 Show/hide the status bar

            The PC keyboard follows the ZX81 matrix. Arrow keys also map to 5/6/7/8.
            LOAD "NAME" and SAVE "NAME" use the tapes/ folder, as in the C example.
            """,
            "Help", JOptionPane.INFORMATION_MESSAGE);
        screen.requestFocusInWindow();
    }
}
