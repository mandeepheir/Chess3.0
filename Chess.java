import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.net.URL;
import java.util.*;
import java.util.List;

/** Swing front end. All the rules live in Board; this class only draws and handles clicks. */
public class Chess extends JPanel {

    /** Board colour themes: light squares, dark squares, frame. */
    private static final String[] THEME_NAMES = {"Classic wood", "Forest green", "Ocean blue", "Slate"};
    private static final Color[][] THEMES = {
        {new Color(240, 217, 181), new Color(181, 136, 99),  new Color(94, 62, 40)},
        {new Color(238, 238, 210), new Color(118, 150, 86),  new Color(48, 68, 42)},
        {new Color(222, 227, 230), new Color(140, 162, 173), new Color(38, 62, 82)},
        {new Color(214, 214, 214), new Color(110, 118, 130), new Color(40, 42, 48)}
    };
    private static final String GLYPHS = "\u265A\u265B\u265C\u265D\u265E\u265F";   // filled K Q R B N P
    private static final String WHITE_GLYPHS = "\u2654\u2655\u2656\u2657\u2658\u2659";
    private static final String GLYPH_ORDER = "KQRBNP";

    private Board board = Board.start();
    private final ArrayList<Board> histBoards = new ArrayList<>();
    private final ArrayList<Move> histMoves = new ArrayList<>();
    private final ArrayList<String> histSan = new ArrayList<>();
    private final Map<Character, Image> images = new HashMap<>();

    private int selR = -1, selC = -1;
    private List<Move> selMoves = new ArrayList<>();
    private Move lastMove, hintMove;
    private boolean thinking, gameOver, vsComputer = true, humanWhite = true, flipped = false;
    private int theme = 0;
    private boolean soundOn = true;
    private boolean lastCapture;
    private String result = "*", endMessage = null;               // PGN result and the "game over" text
    private int startFullmove = 1;
    private boolean startWhite = true;
    private String startFen = null;                               // null = normal starting position

    // chess clock
    private static final String[] TIME_NAMES = {"No clock", "1 min", "3 min", "5 min", "10 min", "3 min + 2 s", "5 min + 3 s"};
    private static final int[] TIME_BASE_SEC = {0, 60, 180, 300, 600, 180, 300};
    private static final int[] TIME_INC_SEC  = {0, 0, 0, 0, 0, 2, 3};
    private int timeChoice = 0;
    private boolean clockOn;
    private long whiteMs, blackMs, incMs, lastTick;
    private final JLabel whiteClock = new JLabel("--:--", SwingConstants.CENTER);
    private final JLabel blackClock = new JLabel("--:--", SwingConstants.CENTER);

    // drag-and-drop and slide animation state
    private boolean dragging;
    private int dragR, dragC, dragX, dragY;
    private Move animMove;
    private float animT;
    private javax.swing.Timer animTimer;

    private int depth = 2, generation = 0;
    private String busyText = "Computer is thinking...";
    private SwingWorker<Move, Void> worker;

    private final JLabel status = new JLabel(" ", SwingConstants.CENTER);
    private final JTextArea movesArea = new JTextArea(14, 16);
    private final JLabel capByWhite = new JLabel(" "), capByBlack = new JLabel(" ");

    public Chess() {
        setPreferredSize(new Dimension(640, 640));
        setMinimumSize(new Dimension(360, 360));
        loadImages();
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { onPress(e.getX(), e.getY()); }
            @Override public void mouseDragged(MouseEvent e)  { if (dragging) { dragX = e.getX(); dragY = e.getY(); repaint(); } }
            @Override public void mouseReleased(MouseEvent e) { onRelease(e.getX(), e.getY()); }
            @Override public void mouseMoved(MouseEvent e)    { updateCursor(e.getX(), e.getY()); }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        for (JLabel l : new JLabel[]{whiteClock, blackClock}) {
            l.setOpaque(true);
            l.setFont(new Font(Font.MONOSPACED, Font.BOLD, 18));
            l.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        }
        lastTick = System.nanoTime();
        new javax.swing.Timer(100, e -> tickClock()).start();
        updateStatus();
        updateClockLabels();
    }

    // -------------------------------------------------------------- resources

    /** Looks for Resources/WP.png etc. on the classpath first, then in the working folder. */
    private void loadImages() {
        for (char k : "PNBRQKpnbrqk".toCharArray()) {
            String colour = Character.isUpperCase(k) ? "W" : "B";
            char t = Character.toUpperCase(k);
            String file = "Resources/" + colour + (t == 'N' ? "kN" : String.valueOf(t)) + ".png";
            URL url = Chess.class.getResource("/" + file);
            Image img = null;
            if (url != null) img = new ImageIcon(url).getImage();
            else if (new File(file).exists()) img = new ImageIcon(file).getImage();
            if (img != null && img.getWidth(null) > 0) images.put(k, img);
        }
    }

    // ---------------------------------------------------------------- drawing

    /** Screen column/row of a board column/row (the board can be flipped). */
    private int viewC(int c) { return flipped ? 7 - c : c; }
    private int viewR(int r) { return flipped ? 7 - r : r; }

    private int squareSize() { return Math.max(1, (int) (Math.min(getWidth(), getHeight()) / 8.7)); }
    private int border() { return Math.max(8, squareSize() * 35 / 100); }
    private int originX() { return (getWidth() - squareSize() * 8) / 2; }
    private int originY() { return (getHeight() - squareSize() * 8) / 2; }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        Color light = THEMES[theme][0], dark = THEMES[theme][1], frame = THEMES[theme][2];
        int s = squareSize(), ox = originX(), oy = originY(), b = border();

        // background, soft shadow and frame
        g.setPaint(new GradientPaint(0, 0, new Color(44, 48, 60), 0, getHeight(), new Color(24, 26, 34)));
        g.fillRect(0, 0, getWidth(), getHeight());
        g.setColor(new Color(0, 0, 0, 90));
        g.fillRoundRect(ox - b + 5, oy - b + 6, 8 * s + 2 * b, 8 * s + 2 * b, 18, 18);
        g.setPaint(new GradientPaint(0, oy - b, frame.brighter(), 0, oy + 8 * s + b, frame.darker()));
        g.fillRoundRect(ox - b, oy - b, 8 * s + 2 * b, 8 * s + 2 * b, 18, 18);
        g.setColor(new Color(0, 0, 0, 120));
        g.drawRect(ox - 1, oy - 1, 8 * s + 1, 8 * s + 1);

        // coordinates live in the frame, so they never cover the pieces
        g.setFont(new Font("SansSerif", Font.BOLD, Math.max(10, b * 6 / 10)));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(new Color(235, 228, 215));
        for (int c = 0; c < 8; c++) {
            String f = String.valueOf((char) ('a' + c));
            g.drawString(f, ox + viewC(c) * s + (s - fm.stringWidth(f)) / 2, oy + 8 * s + (b + fm.getAscent()) / 2 - 1);
        }
        for (int r = 0; r < 8; r++) {
            String n = String.valueOf(8 - r);
            g.drawString(n, ox - (b + fm.stringWidth(n)) / 2, oy + viewR(r) * s + (s + fm.getAscent()) / 2 - 2);
        }

        boolean checkOnMove = board.inCheck(board.whiteToMove);
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                int x = ox + viewC(c) * s, y = oy + viewR(r) * s;
                g.setColor((r + c) % 2 == 0 ? light : dark);
                g.fillRect(x, y, s, s);

                if (lastMove != null && ((r == lastMove.fr && c == lastMove.fc) || (r == lastMove.tr && c == lastMove.tc))) {
                    g.setColor(new Color(255, 214, 10, 105)); g.fillRect(x, y, s, s);
                }
                if (r == selR && c == selC) { g.setColor(new Color(20, 120, 220, 120)); g.fillRect(x, y, s, s); }

                char p = board.sq[r][c];
                if (checkOnMove && (p == 'K' || p == 'k') && Board.isWhite(p) == board.whiteToMove) {   // red glow under the king
                    g.setPaint(new RadialGradientPaint(x + s / 2f, y + s / 2f, s * 0.62f, new float[]{0f, 0.7f, 1f},
                            new Color[]{new Color(235, 30, 30, 230), new Color(235, 30, 30, 110), new Color(235, 30, 30, 0)}));
                    g.fillRect(x, y, s, s);
                }
                boolean hidden = (dragging && r == dragR && c == dragC) || (animMove != null && r == animMove.tr && c == animMove.tc);
                if (p != ' ' && !hidden) drawPiece(g, p, x, y, s);
            }
        }

        for (Move m : selMoves) {                                   // legal destinations
            int x = ox + viewC(m.tc) * s, y = oy + viewR(m.tr) * s;
            g.setColor(new Color(20, 85, 30, 150));
            if (board.sq[m.tr][m.tc] != ' ' || (m.fc != m.tc && Character.toUpperCase(board.sq[m.fr][m.fc]) == 'P')) {
                g.setStroke(new BasicStroke(Math.max(3, s / 12f)));
                g.drawOval(x + s / 12, y + s / 12, s * 10 / 12, s * 10 / 12);
            } else {
                g.fillOval(x + s * 5 / 14, y + s * 5 / 14, s * 4 / 14, s * 4 / 14);
            }
        }
        g.setStroke(new BasicStroke(1f));

        if (hintMove != null) drawArrow(g, hintMove, ox, oy, s);

        if (animMove != null) {                                     // piece sliding from its old square to its new one
            float t = 1f - (1f - animT) * (1f - animT) * (1f - animT);
            float fx = ox + viewC(animMove.fc) * s, fy = oy + viewR(animMove.fr) * s;
            float tx = ox + viewC(animMove.tc) * s, ty = oy + viewR(animMove.tr) * s;
            drawPiece(g, board.sq[animMove.tr][animMove.tc], Math.round(fx + (tx - fx) * t), Math.round(fy + (ty - fy) * t), s);
        }
        if (dragging) {                                             // piece following the mouse
            char p = board.sq[dragR][dragC];
            if (p != ' ') drawPiece(g, p, dragX - s / 2, dragY - s / 2, s);
        }
    }

    private void drawArrow(Graphics2D g, Move m, int ox, int oy, int s) {
        double x1 = ox + viewC(m.fc) * s + s / 2.0, y1 = oy + viewR(m.fr) * s + s / 2.0;
        double x2 = ox + viewC(m.tc) * s + s / 2.0, y2 = oy + viewR(m.tr) * s + s / 2.0;
        double ang = Math.atan2(y2 - y1, x2 - x1), head = s * 0.34;
        double ex = x2 - Math.cos(ang) * head * 0.6, ey = y2 - Math.sin(ang) * head * 0.6;
        g.setColor(new Color(33, 150, 243, 190));
        g.setStroke(new BasicStroke(s * 0.14f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new java.awt.geom.Line2D.Double(x1, y1, ex, ey));
        Polygon tip = new Polygon();
        tip.addPoint((int) x2, (int) y2);
        tip.addPoint((int) (x2 - Math.cos(ang - 0.5) * head), (int) (y2 - Math.sin(ang - 0.5) * head));
        tip.addPoint((int) (x2 - Math.cos(ang + 0.5) * head), (int) (y2 - Math.sin(ang + 0.5) * head));
        g.fillPolygon(tip);
        g.setStroke(new BasicStroke(1f));
    }

    private void drawPiece(Graphics2D g, char p, int x, int y, int s) {
        Image img = images.get(p);
        if (img != null) {
            int pad = s / 12;
            g.setColor(new Color(0, 0, 0, 45));                      // soft contact shadow
            g.fillOval(x + s / 6, y + s * 3 / 4, s * 2 / 3, s / 7);
            g.drawImage(img, x + pad, y + pad, s - 2 * pad, s - 2 * pad, null);
        } else {                                                    // fallback if Resources/ is missing
            int i = GLYPH_ORDER.indexOf(Character.toUpperCase(p));
            String glyph = String.valueOf(GLYPHS.charAt(i));
            boolean white = Board.isWhite(p);
            g.setFont(new Font("SansSerif", Font.PLAIN, s * 3 / 4));
            FontMetrics fm = g.getFontMetrics();
            int gx = x + (s - fm.stringWidth(glyph)) / 2, gy = y + (s + fm.getAscent() - fm.getDescent()) / 2;
            g.setColor(new Color(0, 0, 0, 70));
            g.drawString(glyph, gx + 2, gy + 3);                                   // drop shadow
            g.setColor(white ? Color.BLACK : new Color(235, 235, 235));            // thin outline so both colours stand out
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) g.drawString(glyph, gx + dx, gy + dy);
            g.setColor(white ? Color.WHITE : new Color(30, 30, 30));
            g.drawString(glyph, gx, gy);
        }
    }

    // ------------------------------------------------------------------ input

    private boolean computerTurn() { return vsComputer && board.whiteToMove != humanWhite; }

    /** Board square (row, col) under a pixel, or null if the pixel is outside the board. */
    private int[] squareAt(int px, int py) {
        int s = squareSize();
        if (px < originX() || py < originY()) return null;
        int dc = (px - originX()) / s, dr = (py - originY()) / s;
        if (dr > 7 || dc > 7) return null;
        return new int[]{flipped ? 7 - dr : dr, flipped ? 7 - dc : dc};
    }

    private boolean canInteract() { return !(thinking || gameOver || computerTurn()); }

    private void onPress(int px, int py) {
        int[] sq = squareAt(px, py);
        if (sq == null || !canInteract()) return;
        int r = sq[0], c = sq[1];
        if (selR != -1 && tryMove(r, c, true)) return;                 // click-click: destination of the selected piece
        char p = board.sq[r][c];
        if (p != ' ' && Board.isWhite(p) == board.whiteToMove) {
            select(r, c);
            dragging = true; dragR = r; dragC = c; dragX = px; dragY = py;   // may turn into a drag
        } else clearSelection();
        repaint();
    }

    private void onRelease(int px, int py) {
        if (!dragging) return;
        dragging = false;
        int[] sq = squareAt(px, py);
        if (sq != null && !(sq[0] == dragR && sq[1] == dragC) && tryMove(sq[0], sq[1], false)) return;
        repaint();
    }

    private void updateCursor(int px, int py) {
        int[] sq = squareAt(px, py);
        boolean hand = false;
        if (sq != null && canInteract()) {
            char p = board.sq[sq[0]][sq[1]];
            hand = p != ' ' && Board.isWhite(p) == board.whiteToMove;
        }
        setCursor(Cursor.getPredefinedCursor(hand ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
    }

    /** Plays the selected piece to (r,c) if that is a legal destination. */
    private boolean tryMove(int r, int c, boolean animate) {
        List<Move> matches = new ArrayList<>();
        for (Move m : selMoves) if (m.tr == r && m.tc == c) matches.add(m);
        if (matches.isEmpty()) return false;
        dragging = false;
        play(matches.get(0).promo != 0 ? askPromotion(matches) : matches.get(0), animate);
        return true;
    }

    private void select(int r, int c) {
        selR = r; selC = c;
        selMoves = new ArrayList<>();
        for (Move m : board.legalMoves()) if (m.fr == r && m.fc == c) selMoves.add(m);
    }

    private void clearSelection() { selR = -1; selC = -1; selMoves = new ArrayList<>(); }

    private Move askPromotion(List<Move> options) {
        String[] names = {"Queen", "Rook", "Bishop", "Knight"};
        char[] codes = {'Q', 'R', 'B', 'N'};
        int choice = JOptionPane.showOptionDialog(this, "Promote pawn to:", "Pawn promotion",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, names, names[0]);
        char want = choice >= 0 ? codes[choice] : 'Q';
        for (Move m : options) if (m.promo == want) return m;
        return options.get(0);
    }

    // ------------------------------------------------------------- game flow

    private void startAnimation(Move m) {
        if (animTimer != null) animTimer.stop();
        animMove = m;
        animT = 0f;
        final long t0 = System.nanoTime();
        animTimer = new javax.swing.Timer(15, null);
        animTimer.addActionListener(e -> {
            animT = Math.min(1f, (System.nanoTime() - t0) / 190_000_000f);
            if (animT >= 1f) { animMove = null; animTimer.stop(); }
            repaint();
        });
        animTimer.start();
    }

    private void stopAnimation() {
        if (animTimer != null) animTimer.stop();
        animMove = null;
    }

    private void play(Move m) { play(m, true); }

    private void play(Move m, boolean animate) {
        char moving = board.sq[m.fr][m.fc];
        lastCapture = board.sq[m.tr][m.tc] != ' ' || (Character.toUpperCase(moving) == 'P' && m.fc != m.tc);
        boolean moverIsWhite = board.whiteToMove;
        histBoards.add(board);
        histMoves.add(m);
        histSan.add(board.san(m));                                  // notation must be made before the move is played
        Board next = board.copy();
        next.apply(m);
        board = next;
        if (clockOn) { if (moverIsWhite) whiteMs += incMs; else blackMs += incMs; }     // increment for the side that just moved
        lastMove = m;
        hintMove = null;
        clearSelection();
        refreshMoveList();
        if (animate) startAnimation(m); else stopAnimation();
        afterMove();
    }

    private void afterMove() {
        Board.Status st = board.status();
        updateStatus();
        repaint();
        if (st != Board.Status.PLAYING) {
            String pgn = st == Board.Status.CHECKMATE ? (board.whiteToMove ? "0-1" : "1-0") : "1/2-1/2";
            endGame(status.getText(), pgn);
        } else {
            sound(board.inCheck(board.whiteToMove) ? Sounds.Effect.CHECK : lastCapture ? Sounds.Effect.CAPTURE : Sounds.Effect.MOVE);
            if (computerTurn()) computerMove();
        }
    }

    /** Ends the game for any reason (mate, draw, resignation, flag fall) and tells the player. */
    private void endGame(String message, String pgnResult) {
        generation++;
        if (worker != null) worker.cancel(true);
        thinking = false;
        gameOver = true;
        endMessage = message;
        result = pgnResult;
        status.setText(message);
        clearSelection();
        dragging = false;
        sound(Sounds.Effect.END);
        repaint();
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, message, "Game over", JOptionPane.INFORMATION_MESSAGE));
    }

    private void sound(Sounds.Effect e) { if (soundOn) Sounds.play(e); }

    private void resign() {
        if (gameOver || thinking) return;
        String loser = vsComputer ? (humanWhite ? "White" : "Black") : (board.whiteToMove ? "White" : "Black");
        if (JOptionPane.showConfirmDialog(this, loser + " resigns?", "Resign", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        boolean whiteLoses = loser.equals("White");
        endGame(loser + " resigned. " + (whiteLoses ? "Black" : "White") + " wins.", whiteLoses ? "0-1" : "1-0");
    }

    // ------------------------------------------------------------------ clock

    private void tickClock() {
        long now = System.nanoTime();
        long dt = (now - lastTick) / 1_000_000L;
        lastTick = now;
        if (clockOn && !gameOver) {
            if (board.whiteToMove) whiteMs -= dt; else blackMs -= dt;
            if (whiteMs <= 0) { whiteMs = 0; updateClockLabels(); endGame("Time's up! Black wins on time.", "0-1"); return; }
            if (blackMs <= 0) { blackMs = 0; updateClockLabels(); endGame("Time's up! White wins on time.", "1-0"); return; }
        }
        updateClockLabels();
    }

    private static String fmt(long ms) {
        long t = Math.max(0, (ms + 999) / 1000);
        return String.format("%d:%02d", t / 60, t % 60);
    }

    private void updateClockLabels() {
        whiteClock.setText(clockOn ? "\u2654 " + fmt(whiteMs) : "\u2654 --:--");
        blackClock.setText(clockOn ? "\u265A " + fmt(blackMs) : "\u265A --:--");
        Color active = new Color(200, 230, 200), idle = new Color(235, 235, 235);
        boolean liveWhite = clockOn && !gameOver && board.whiteToMove, liveBlack = clockOn && !gameOver && !board.whiteToMove;
        whiteClock.setBackground(liveWhite ? active : idle);
        blackClock.setBackground(liveBlack ? active : idle);
        whiteClock.setForeground(clockOn && whiteMs < 10_000 ? new Color(190, 30, 30) : Color.BLACK);
        blackClock.setForeground(clockOn && blackMs < 10_000 ? new Color(190, 30, 30) : Color.BLACK);
    }

    private void resetClock() {
        clockOn = TIME_BASE_SEC[timeChoice] > 0;
        whiteMs = blackMs = TIME_BASE_SEC[timeChoice] * 1000L;
        incMs = TIME_INC_SEC[timeChoice] * 1000L;
        lastTick = System.nanoTime();
        updateClockLabels();
    }

    /** The search runs on a background thread so the window never freezes. */
    private void computerMove() {
        thinking = true;
        busyText = "Computer is thinking...";
        updateStatus();
        final Board snapshot = board.copy();
        final int myGeneration = generation, level = depth;
        final long budget = !clockOn ? Long.MAX_VALUE
                : Math.max(120, (board.whiteToMove ? whiteMs : blackMs) / 25 + incMs / 2);    // use a slice of the remaining time
        worker = new SwingWorker<Move, Void>() {
            @Override protected Move doInBackground() throws Exception {
                long start = System.currentTimeMillis();
                Move m = ChessAI.bestMove(snapshot, level, budget);
                long wait = 450 - (System.currentTimeMillis() - start);      // short pause so replies feel natural
                if (wait > 0) Thread.sleep(wait);
                return m;
            }
            @Override protected void done() {
                if (isCancelled() || myGeneration != generation) return;     // game was reset meanwhile
                thinking = false;
                try { Move m = get(); if (m != null) play(m); }
                catch (Exception ex) { ex.printStackTrace(); updateStatus(); }
            }
        };
        worker.execute();
    }

    /** Asks the AI for a good move for the human and highlights it in blue. */
    private void hint() {
        if (thinking || gameOver || computerTurn()) return;
        thinking = true;
        busyText = "Looking for a hint...";
        updateStatus();
        final Board snapshot = board.copy();
        final int myGeneration = generation;
        worker = new SwingWorker<Move, Void>() {
            @Override protected Move doInBackground() { return ChessAI.bestMove(snapshot, 3); }
            @Override protected void done() {
                if (isCancelled() || myGeneration != generation) return;
                thinking = false;
                try { hintMove = get(); } catch (Exception ex) { ex.printStackTrace(); }
                updateStatus();
                repaint();
            }
        };
        worker.execute();
    }

    private void newGame() {
        generation++;
        stopAnimation();
        if (worker != null) worker.cancel(true);                    // stop any search still running
        thinking = false; gameOver = false;
        board = Board.start();
        histBoards.clear(); histMoves.clear(); histSan.clear();
        lastMove = null; hintMove = null;
        endMessage = null; result = "*"; startFen = null; startFullmove = 1; startWhite = true;
        resetClock();
        clearSelection();
        refreshMoveList();
        updateStatus();
        repaint();
        if (computerTurn()) computerMove();                         // you chose Black: the computer opens
    }

    private void undo() {
        if (thinking || histBoards.isEmpty()) return;
        do {                                                                 // vs computer: undo back to your turn
            board = histBoards.remove(histBoards.size() - 1);
            histMoves.remove(histMoves.size() - 1);
            histSan.remove(histSan.size() - 1);
        } while (computerTurn() && !histBoards.isEmpty());
        stopAnimation();
        gameOver = false;
        endMessage = null; result = "*";
        hintMove = null;
        lastMove = histMoves.isEmpty() ? null : histMoves.get(histMoves.size() - 1);
        clearSelection();
        refreshMoveList();
        updateStatus();
        repaint();
        if (computerTurn()) computerMove();                         // back at the start as Black: computer opens again
    }

    private void updateStatus() {
        if (endMessage != null) { status.setText(endMessage); return; }
        String side = board.whiteToMove ? "White" : "Black";
        switch (board.status()) {
            case CHECKMATE: status.setText("Checkmate! " + (board.whiteToMove ? "Black" : "White") + " wins."); break;
            case STALEMATE: status.setText("Stalemate. The game is a draw."); break;
            case DRAW:      status.setText("Draw: " + board.drawReason() + "."); break;
            default:
                if (thinking) status.setText(busyText);
                else status.setText(side + " to move" + (board.inCheck(board.whiteToMove) ? " (check!)" : ""));
        }
    }

    /** Move list text. Works for games that start from a loaded position too (numbering and "5... e5" for Black). */
    private String movesText(boolean pgn) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < histSan.size(); i++) {
            boolean whiteMove = (i % 2 == 0) == startWhite;
            int number = startFullmove + (i + (startWhite ? 0 : 1)) / 2;
            if (whiteMove) sb.append(number).append(". ");
            else if (i == 0) sb.append(number).append("... ");
            sb.append(histSan.get(i)).append(pgn ? " " : (whiteMove ? "   " : "\n"));
        }
        return sb.toString();
    }

    private void refreshMoveList() {
        StringBuilder sb = new StringBuilder(movesText(false));
        movesArea.setText(sb.toString());
        movesArea.setCaretPosition(movesArea.getDocument().getLength());
        updateCaptured();
    }

    /** Shows which pieces each side has captured and who is ahead in material. */
    private void updateCaptured() {
        final String order = "QRBNP";
        final int[] start = {1, 2, 2, 2, 8}, worth = {9, 5, 3, 3, 1};
        int[] white = new int[5], black = new int[5];
        for (char[] row : board.sq)
            for (char p : row) {
                int i = order.indexOf(Character.toUpperCase(p));
                if (i >= 0) { if (Board.isWhite(p)) white[i]++; else black[i]++; }
            }
        StringBuilder byWhite = new StringBuilder(), byBlack = new StringBuilder();
        int whitePoints = 0, blackPoints = 0;
        for (int i = 0; i < 5; i++) {
            whitePoints += white[i] * worth[i]; blackPoints += black[i] * worth[i];
            int g = GLYPH_ORDER.indexOf(order.charAt(i));
            for (int k = 0; k < Math.max(0, start[i] - black[i]); k++) byWhite.append(GLYPHS.charAt(g));
            for (int k = 0; k < Math.max(0, start[i] - white[i]); k++) byBlack.append(WHITE_GLYPHS.charAt(g));
        }
        int diff = whitePoints - blackPoints;
        capByWhite.setText("<html><font size=-1>White took</font> " + byWhite + (diff > 0 ? " <b>+" + diff + "</b>" : "") + "&nbsp;</html>");
        capByBlack.setText("<html><font size=-1>Black took</font> " + byBlack + (diff < 0 ? " <b>+" + (-diff) + "</b>" : "") + "&nbsp;</html>");
    }

    // ------------------------------------------------------------------ setup

    // ------------------------------------------------------- FEN / PGN tools

    private void copyToClipboard(String text, String what) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
            JOptionPane.showMessageDialog(this, what + " copied to the clipboard.", "Copied", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not access the clipboard.", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private String buildPgn() {
        String date = java.time.LocalDate.now().toString().replace('-', '.');
        String white = !vsComputer ? "Player 1" : humanWhite ? "Player" : "Computer";
        String black = !vsComputer ? "Player 2" : humanWhite ? "Computer" : "Player";
        StringBuilder sb = new StringBuilder();
        sb.append("[Event \"Casual game\"]\n[Site \"Java Swing Chess\"]\n[Date \"").append(date).append("\"]\n");
        sb.append("[Round \"-\"]\n[White \"").append(white).append("\"]\n[Black \"").append(black).append("\"]\n");
        sb.append("[Result \"").append(result).append("\"]\n");
        if (startFen != null) sb.append("[SetUp \"1\"]\n[FEN \"").append(startFen).append("\"]\n");
        return sb.append('\n').append(movesText(true).trim()).append(' ').append(result).append('\n').toString();
    }

    /** Lets the player paste any position (FEN) and continue from there. */
    private void loadFen() {
        if (thinking) return;
        String text = JOptionPane.showInputDialog(this, "Paste a position in FEN notation:", board.toFen());
        if (text == null || text.trim().isEmpty()) return;
        Board loaded;
        try { loaded = Board.fromFen(text.trim()); }
        catch (Exception ex) { loaded = null; }
        if (loaded == null || !loaded.isValid()) {
            JOptionPane.showMessageDialog(this, "That is not a valid chess position.", "Load FEN", JOptionPane.ERROR_MESSAGE);
            return;
        }
        generation++;
        stopAnimation();
        if (worker != null) worker.cancel(true);
        thinking = false; gameOver = false;
        board = loaded;
        histBoards.clear(); histMoves.clear(); histSan.clear();
        lastMove = null; hintMove = null; endMessage = null; result = "*";
        startFen = board.toFen(); startFullmove = board.fullmove; startWhite = board.whiteToMove;
        clearSelection();
        resetClock();
        refreshMoveList();
        updateStatus();
        repaint();
        Board.Status st = board.status();
        if (st != Board.Status.PLAYING) {
            endGame(status.getText(), st == Board.Status.CHECKMATE ? (board.whiteToMove ? "0-1" : "1-0") : "1/2-1/2");
        } else if (computerTurn()) {
            computerMove();
        }
    }

    // ------------------------------------------------------------------ setup

    private JPanel buildSidePanel() {
        JPanel side = new JPanel();
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JComboBox<String> mode = new JComboBox<>(new String[]{"Play vs computer", "Two players"});
        JComboBox<String> colour = new JComboBox<>(new String[]{"Play as White", "Play as Black"});
        JComboBox<String> level = new JComboBox<>(new String[]{"Easy", "Medium", "Hard", "Expert"});
        JComboBox<String> time = new JComboBox<>(TIME_NAMES);
        JComboBox<String> themes = new JComboBox<>(THEME_NAMES);
        level.setSelectedIndex(1);
        capByWhite.setFont(new Font("SansSerif", Font.PLAIN, 18));
        capByBlack.setFont(new Font("SansSerif", Font.PLAIN, 18));

        JButton newGame = new JButton("New game"), undo = new JButton("Undo");
        JButton hint = new JButton("Hint"), flip = new JButton("Flip board");
        JButton resign = new JButton("Resign");
        JToggleButton sound = new JToggleButton("Sound: on", true);
        JButton copyFen = new JButton("Copy FEN"), loadFen = new JButton("Load FEN");
        JButton copyPgn = new JButton("Copy PGN (save game)");

        mode.addActionListener(e -> {
            vsComputer = mode.getSelectedIndex() == 0;
            level.setEnabled(vsComputer); colour.setEnabled(vsComputer);
            newGame();
        });
        colour.addActionListener(e -> { humanWhite = colour.getSelectedIndex() == 0; flipped = !humanWhite; newGame(); });
        level.addActionListener(e -> depth = level.getSelectedIndex() + 1);
        time.addActionListener(e -> { timeChoice = time.getSelectedIndex(); newGame(); });
        themes.addActionListener(e -> { theme = themes.getSelectedIndex(); repaint(); });
        newGame.addActionListener(e -> newGame());
        undo.addActionListener(e -> undo());
        hint.addActionListener(e -> hint());
        flip.addActionListener(e -> { flipped = !flipped; repaint(); });
        resign.addActionListener(e -> resign());
        sound.addActionListener(e -> { soundOn = sound.isSelected(); sound.setText(soundOn ? "Sound: on" : "Sound: off"); });
        copyFen.addActionListener(e -> copyToClipboard(board.toFen(), "FEN"));
        loadFen.addActionListener(e -> loadFen());
        copyPgn.addActionListener(e -> copyToClipboard(buildPgn(), "PGN"));

        movesArea.setEditable(false);
        movesArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        JScrollPane scroll = new JScrollPane(movesArea);
        scroll.setAlignmentX(Component.LEFT_ALIGNMENT);

        for (JComponent combo : new JComponent[]{mode, colour, level, time, themes}) side.add(row(combo));
        side.add(Box.createVerticalStrut(8));
        side.add(row(blackClock));                                  // the clock of the side at the top of the board
        side.add(Box.createVerticalStrut(4));
        side.add(row(whiteClock));
        side.add(Box.createVerticalStrut(8));
        side.add(row(newGame, undo));
        side.add(row(hint, flip));
        side.add(row(resign, sound));
        side.add(row(copyFen, loadFen));
        side.add(row(copyPgn));
        side.add(Box.createVerticalStrut(8));
        side.add(row(capByWhite));
        side.add(row(capByBlack));
        side.add(Box.createVerticalStrut(6));
        side.add(new JLabel("Moves"));
        side.add(scroll);
        return side;
    }

    /** One row of the side panel holding one or two equally wide components. */
    private static JPanel row(JComponent... parts) {
        JPanel p = new JPanel(new GridLayout(1, parts.length, 6, 0));
        for (JComponent c : parts) p.add(c);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        p.setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 0));
        return p;
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            Chess chess = new Chess();
            JFrame frame = new JFrame("Chess");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            chess.status.setFont(chess.status.getFont().deriveFont(Font.BOLD, 16f));
            chess.status.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            frame.add(chess.status, BorderLayout.NORTH);
            frame.add(chess, BorderLayout.CENTER);
            frame.add(chess.buildSidePanel(), BorderLayout.EAST);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }
}