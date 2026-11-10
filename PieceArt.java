import java.awt.*;
import java.awt.geom.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Chess pieces drawn with vector shapes, so they stay sharp at any board size and need no image files.
 * Each piece is designed on a 100 x 100 grid and scaled to the square.
 */
public final class PieceArt {

    private static final Map<Character, Shape> BODY = new HashMap<>();
    private static final Map<Character, Shape[]> DETAIL = new HashMap<>();    // extra shapes drawn on top (collars, eyes ...)

    private PieceArt() { }

    static {
        // ---- pawn
        Path2D.Double pawn = new Path2D.Double();
        pawn.moveTo(34, 80); pawn.curveTo(36, 66, 43, 60, 44, 50); pawn.lineTo(56, 50); pawn.curveTo(57, 60, 64, 66, 66, 80); pawn.closePath();
        BODY.put('P', union(pawn, new Ellipse2D.Double(38, 22, 24, 24), new RoundRectangle2D.Double(36, 45, 28, 7, 5, 5), base()));
        DETAIL.put('P', new Shape[0]);

        // ---- rook
        Path2D.Double rook = new Path2D.Double();
        rook.moveTo(33, 80); rook.lineTo(37, 42); rook.lineTo(63, 42); rook.lineTo(67, 80); rook.closePath();
        Path2D.Double crown = new Path2D.Double();
        crown.moveTo(33, 44); crown.lineTo(33, 20); crown.lineTo(41, 20); crown.lineTo(41, 28); crown.lineTo(46, 28); crown.lineTo(46, 20);
        crown.lineTo(54, 20); crown.lineTo(54, 28); crown.lineTo(59, 28); crown.lineTo(59, 20); crown.lineTo(67, 20); crown.lineTo(67, 44); crown.closePath();
        BODY.put('R', union(rook, crown, new RoundRectangle2D.Double(30, 38, 40, 7, 4, 4), base()));
        DETAIL.put('R', new Shape[0]);

        // ---- knight (faces left)
        Path2D.Double knight = new Path2D.Double();
        knight.moveTo(30, 82); knight.curveTo(30, 66, 38, 58, 46, 50);
        knight.curveTo(40, 52, 32, 50, 26, 44); knight.curveTo(22, 40, 26, 34, 32, 30);
        knight.lineTo(34, 18); knight.lineTo(42, 24);
        knight.curveTo(56, 20, 74, 32, 74, 56); knight.curveTo(74, 66, 72, 74, 72, 82); knight.closePath();
        BODY.put('N', union(knight, base()));
        DETAIL.put('N', new Shape[]{new Ellipse2D.Double(38, 30, 5, 5), new Ellipse2D.Double(26, 38, 3.5, 3.5)});

        // ---- bishop
        Path2D.Double bishop = new Path2D.Double();
        bishop.moveTo(50, 20); bishop.curveTo(66, 30, 68, 48, 57, 58); bishop.lineTo(62, 76); bishop.lineTo(38, 76); bishop.lineTo(43, 58);
        bishop.curveTo(32, 48, 34, 30, 50, 20); bishop.closePath();
        BODY.put('B', union(bishop, new Ellipse2D.Double(44, 9, 12, 12), new RoundRectangle2D.Double(34, 70, 32, 8, 5, 5), base()));
        DETAIL.put('B', new Shape[]{new Line2D.Double(46, 34, 55, 45)});

        // ---- queen
        Path2D.Double queen = new Path2D.Double();
        queen.moveTo(30, 82); queen.lineTo(22, 34); queen.lineTo(33, 54); queen.lineTo(36, 26); queen.lineTo(44, 54); queen.lineTo(50, 22);
        queen.lineTo(56, 54); queen.lineTo(64, 26); queen.lineTo(67, 54); queen.lineTo(78, 34); queen.lineTo(70, 82); queen.closePath();
        BODY.put('Q', union(queen, new Ellipse2D.Double(17, 27, 10, 10), new Ellipse2D.Double(31, 19, 10, 10), new Ellipse2D.Double(45, 15, 10, 10),
                new Ellipse2D.Double(59, 19, 10, 10), new Ellipse2D.Double(73, 27, 10, 10), new RoundRectangle2D.Double(32, 70, 36, 8, 5, 5), base()));
        DETAIL.put('Q', new Shape[0]);

        // ---- king
        Path2D.Double king = new Path2D.Double();
        king.moveTo(33, 82); king.lineTo(29, 52); king.curveTo(29, 40, 40, 36, 50, 36); king.curveTo(60, 36, 71, 40, 71, 52); king.lineTo(67, 82); king.closePath();
        BODY.put('K', union(king, new RoundRectangle2D.Double(45, 8, 10, 28, 3, 3), new RoundRectangle2D.Double(37, 15, 26, 9, 3, 3),
                new RoundRectangle2D.Double(31, 70, 38, 8, 5, 5), base()));
        DETAIL.put('K', new Shape[0]);
    }

    private static Shape base() { return new RoundRectangle2D.Double(26, 80, 48, 10, 7, 7); }

    private static Shape union(Shape... parts) {
        Area a = new Area();
        for (Shape p : parts) a.add(new Area(p));
        return a;
    }

    /** Draws one piece (FEN letter: upper case = White) into the square at (x, y) with side s. */
    public static void draw(Graphics2D g0, char piece, int x, int y, int s) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        boolean white = Character.isUpperCase(piece);
        char key = Character.toUpperCase(piece);
        Shape body = BODY.get(key);
        if (body == null) { g.dispose(); return; }

        g.translate(x, y);
        g.scale(s / 100.0, s / 100.0);

        g.setColor(new Color(0, 0, 0, 55));                                        // soft contact shadow
        g.fill(new Ellipse2D.Double(24, 84, 54, 9));

        Color top = white ? new Color(255, 255, 255) : new Color(92, 92, 98);
        Color bottom = white ? new Color(205, 197, 180) : new Color(22, 22, 26);
        g.setPaint(new GradientPaint(0, 8, top, 0, 90, bottom));
        g.fill(body);

        Color line = white ? new Color(48, 44, 40) : new Color(0, 0, 0);           // outline
        g.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(line);
        g.draw(body);

        // side highlight so the piece looks rounded
        Shape clip = g.getClip();
        g.clip(body);
        g.setPaint(new GradientPaint(30, 0, new Color(255, 255, 255, white ? 120 : 55), 56, 0, new Color(255, 255, 255, 0)));
        g.fill(new Rectangle2D.Double(0, 0, 60, 100));
        g.setClip(clip);

        Shape[] details = DETAIL.get(key);
        if (details != null) {
            for (Shape d : details) {
                if (d instanceof Line2D) {                                          // the bishop's slit
                    g.setStroke(new BasicStroke(3.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.setColor(white ? new Color(48, 44, 40) : new Color(210, 210, 215));
                    g.draw(d);
                } else {                                                            // eyes, nostrils
                    g.setColor(white ? new Color(48, 44, 40) : new Color(235, 235, 240));
                    g.fill(d);
                }
            }
        }
        g.dispose();
    }
}