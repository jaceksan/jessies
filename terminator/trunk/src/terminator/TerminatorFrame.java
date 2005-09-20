package terminator;

import e.gui.*;
import e.util.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import javax.swing.*;
import javax.swing.Timer;
import javax.swing.event.*;
import terminator.view.*;

public class TerminatorFrame extends JFrame {
	private Terminator terminator;
	private Dimension terminalSize;
	private JTabbedPane tabbedPane;
	
	private ArrayList<JTerminalPane> terminals = new ArrayList<JTerminalPane>();

	public TerminatorFrame(Terminator terminator, JTerminalPaneFactory[] paneFactories) {
		super(Options.getSharedInstance().getTitle());
		this.terminator = terminator;
		JTerminalPane[] panes = new JTerminalPane[paneFactories.length];
		for (int i = 0; i < paneFactories.length; ++i) {
			panes[i] = paneFactories[i].create();
			terminals.add(panes[i]);
		}
		initFrame();
		initFocus();
		for (JTerminalPane pane : panes) {
			pane.start();
		}
	}
	
	/**
	 * Quantizes any resize of the frame so that our terminals have an
	 * integer number of rows and columns. X11 provides direct support
	 * for communicating this desire to the window manager, but we can't
	 * use that, and it wouldn't work on other OSes anyway.
	 * 
	 * This code is based on hack #33 "Window Snapping" from O'Reilly's
	 * book "Swing Hacks". (Their hack doesn't actually work, at least on
	 * Mac OS.)
	 */
	private void initSizeMonitoring() {
		class SizeMonitor extends ComponentAdapter {
			private Dimension lastSize;
			private boolean syntheticResize = false;
			
			@Override
			public void componentShown(ComponentEvent e) {
				lastSize = getSize();
			}
			
			@Override
			public void componentResized(ComponentEvent e) {
				if (lastSize == null || syntheticResize) {
					return;
				}
				Dimension charSize = terminals.get(0).getTextPane().getCharUnitSize();
				Dimension suggestedSize = getSize();
				Dimension newSize = new Dimension();
				newSize.width = suggestedSize.width - (suggestedSize.width - lastSize.width) % charSize.width;
				newSize.height = suggestedSize.height - (suggestedSize.height - lastSize.height) % charSize.height;
				syntheticResize = true;
				setSize(newSize);
				syntheticResize = false;
			}
		}
		addComponentListener(new SizeMonitor());
	}
	
	public void updateFrameTitle() {
		StringBuilder title = new StringBuilder();
		if (terminalSize != null) {
			title.append("[").append(terminalSize.width).append(" x ").append(terminalSize.height).append("] ");
		}
		if (terminals.size() >= 1) {
			JTerminalPane pane;
			if (tabbedPane == null) {
				pane = terminals.get(0);
			} else {
				pane = (JTerminalPane) tabbedPane.getSelectedComponent();
			}
			if (pane != null) {
				title.append(pane.getName());
			}
		}
		setTitle(title.toString());
	}
	
	/**
	 * An icon to use for each frame, if the terminator shell script suggested one.
	 * We don't use this on Mac OS, because there windows don't have icons
	 * unless the window's contents are themselves a graphic. On Linux, we use
	 * one of the available GNOME icons so we look like a terminal rather than a
	 * generic 'window'.
	 */
	private static final Image FRAME_ICON;
	static {
		String iconFile = System.getProperty("terminator.frame.icon");
		FRAME_ICON = (iconFile != null) ? new ImageIcon(iconFile).getImage() : null;
	}
	
	private void initIcon() {
		if (FRAME_ICON != null) {
			setIconImage(FRAME_ICON);
		}
	}
	
	private void initFrame() {
		setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		if (GuiUtilities.isMacOs() == false) {
			setBackground(Options.getSharedInstance().getColor("background"));
		}
		initIcon();
		
		if (Options.getSharedInstance().shouldUseMenuBar()) {
			setJMenuBar(new TerminatorMenuBar());
		}
		addWindowListener(new WindowAdapter() {
			public void windowClosed(WindowEvent event) {
				for (int i = 0; i < terminals.size(); ++i) {
					JTerminalPane terminal = terminals.get(i);
					terminal.destroyProcess();
				}
				terminator.frameClosed(TerminatorFrame.this);
			}
		});
		initTerminals();
		pack();
		setLocationByPlatform(true);
		setVisible(true);
		initSizeMonitoring();
		
		WindowMenu.getSharedInstance().addWindow(this);
	}
	
	private void initTerminals() {
		if (terminals.size() == 1) {
			initSingleTerminal();
		} else {
			initTabbedTerminals();
		}
	}
	
	/**
	 * Give focus to the first terminal.
	 */
	private void initFocus() {
		terminals.get(0).requestFocus();
	}
	
	private void initSingleTerminal() {
		JTerminalPane terminalPane = terminals.get(0);
		setContentPane(terminalPane);
		setTitle(terminalPane.getName());
	}
	
	/**
	 * Switches to a tabbed-pane UI where we can have one tab per terminal.
	 */
	private void switchToTabbedPane() {
		if (tabbedPane != null) {
			return;
		}
		
		JComponent oldContentPane = (JComponent) getContentPane();
		Dimension initialSize = oldContentPane.getSize();
		
		tabbedPane = new JTabbedPane();
		tabbedPane.addChangeListener(new TerminalFocuser());
		ComponentUtilities.disableFocusTraversal(tabbedPane);
		
		// The tabs themselves (the components with the labels)
		// shouldn't be able to get the focus. If they can, clicking
		// on an already-selected tab takes focus away from the
		// associated terminal, which is annoying.
		tabbedPane.setFocusable(false);
		
		if (oldContentPane instanceof JTerminalPane) {
			addPaneToUI((JTerminalPane) oldContentPane);
		}
		
		setContentPane(tabbedPane);
		validate();
		
		Dimension finalSize = oldContentPane.getSize();
		fixTerminalSizesAfterAddingOrRemovingTabbedPane(initialSize, finalSize);
	}
	
	/**
	 * Switches to a simple UI where we can have only one terminal.
	 */
	private void switchToSinglePane() {
		JTerminalPane soleSurvivor = terminals.get(0);
		Dimension initialSize = soleSurvivor.getSize();
		
		soleSurvivor.invalidate();
		setContentPane(soleSurvivor);
		validate();
		
		soleSurvivor.requestFocus();
		tabbedPane = null;
		
		Dimension finalSize = getContentPane().getSize();
		fixTerminalSizesAfterAddingOrRemovingTabbedPane(initialSize, finalSize);
		
		updateFrameTitle();
	}
	
	/**
	 * Ensures that when we change tab, we give focus to that terminal.
	 */
	private class TerminalFocuser implements ChangeListener {
		public void stateChanged(ChangeEvent e) {
			final Component selected = tabbedPane.getSelectedComponent();
			if (selected != null) {
				// I dislike the invokeLater, but it's sadly necessary.
				EventQueue.invokeLater(new Runnable() {
					public void run() {
						selected.requestFocus();
					}
				});
			}
			updateFrameTitle();
		}
	}

	/**
	 * Increases the size of the frame based on the amount of space taken
	 * away from the terminal to insert the tabbed pane. The end result
	 * should be that the terminal size remains constant but the window
	 * grows.
	 */
	private void fixTerminalSizesAfterAddingOrRemovingTabbedPane(Dimension initialSize, Dimension finalSize) {
		Dimension size = getSize();
		size.height += (initialSize.height - finalSize.height);
		size.width += (initialSize.width - finalSize.width);
		setSize(size);
	}
	
	private void initTabbedTerminals() {
		switchToTabbedPane();
		for (int i = 0; i < terminals.size(); ++i) {
			JTerminalPane terminalPane = terminals.get(i);
			addPaneToUI(terminalPane);
		}
	}
	
	public void switchToNextTab() {
		if (tabbedPane != null) {
			setSelectedTab(tabbedPane.getSelectedIndex() + 1);
		}
	}
	
	public void switchToPreviousTab() {
		if (tabbedPane != null) {
			setSelectedTab(tabbedPane.getSelectedIndex() - 1);
		}
	}
	
	private void setSelectedTab(int index) {
		int tabCount = tabbedPane.getTabCount();
		tabbedPane.setSelectedIndex((index + tabCount) % tabCount);
	}

	/**
	 * Removes the given terminal. If this is the last terminal, close
	 * the window.
	 */
	public void closeTerminalPane(JTerminalPane victim) {
		terminals.remove(victim);
		if (tabbedPane != null) {
			closeTab(victim);
		} else {
			closeWindow();
		}
	}
	
	/**
	 * Implements closeTerminalPane.
	 */
	private void closeTab(JTerminalPane victim) {
		tabbedPane.remove(victim);
		if (tabbedPane.getTabCount() == 0) {
			closeWindow();
		} else if (tabbedPane.getTabCount() == 1) {
			switchToSinglePane();
		} else {
			// Just hand focus to the visible tab's terminal. We
			// do this later because otherwise Swing seems to give
			// the focus to the tab itself, rather than the
			// component on the tab.
			EventQueue.invokeLater(new Runnable() {
				public void run() {
					tabbedPane.getSelectedComponent().requestFocus();
				}
			});
		}
	}
	
	/**
	 * Implements closeTerminalPane.
	 */
	private void closeWindow() {
		setVisible(false);
		dispose();
	}
	
	private Timer terminalSizeTimer = null;

	public void setTerminalSize(Dimension size) {
		this.terminalSize = size;
		updateFrameTitle();
		if (terminalSizeTimer != null) {
			terminalSizeTimer.stop();
		}
		terminalSizeTimer = new Timer(2000, new ActionListener() {
			public void actionPerformed(ActionEvent event) {
				terminalSizeTimer = null;
				terminalSize = null;
				updateFrameTitle();
			}
		});
		terminalSizeTimer.setRepeats(false);
		terminalSizeTimer.start();
	}
	
	public void terminalNameChanged(JTerminalPane terminal) {
		if (tabbedPane != null) {
			int index = tabbedPane.indexOfComponent(terminal);
			tabbedPane.setTitleAt(index, terminal.getName());
		}
		updateFrameTitle();
	}
	
	public void openNewTab() {
		addPane(JTerminalPane.newShell(), true);
	}
	
	private void addPane(JTerminalPane newPane, boolean focusOnNewTab) {
		terminals.add(newPane);
		addPaneToUI(newPane);
		newPane.start();
		if (focusOnNewTab) {
			tabbedPane.setSelectedIndex(tabbedPane.getTabCount() - 1);
		}
		updateFrameTitle();
	}
	
	private void addPaneToUI(JTerminalPane newPane) {
		switchToTabbedPane();
		// FIXME: in 6.0, Sun 4499556 "Use arbitrary (J)Components as
		// JTabbedPane tab labels" is fixed, so we can improve this to
		// add newPane.getOutputSpinner() using setTabComponentAt.
		tabbedPane.addTab(newPane.getName(), newPane);
	}
}
