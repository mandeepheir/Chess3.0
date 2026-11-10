import java.util.*;
public class PerftTest {
    static long perft(Board b, int d) {
        if (d == 0) return 1;
        long n = 0;
        for (Move m : b.legalMoves()) { Board c = b.copyLite(); c.apply(m); n += perft(c, d - 1); }
        return n;
    }
    static void check(String name, String fen, int d, long expect) {
        long got = perft(Board.fromFen(fen), d);
        System.out.println((got == expect ? "OK   " : "FAIL ") + name + " depth " + d + ": " + got + " (expected " + expect + ")");
    }
    public static void main(String[] a) {
        String start = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
        String kiwi = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1";
        String pos3 = "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1";
        String pos4 = "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1";
        String pos5 = "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8";
        check("start", start, 4, 197281);
        check("kiwipete", kiwi, 3, 97862);
        check("pos3", pos3, 4, 43238);
        check("pos4", pos4, 3, 9467);
        check("pos5", pos5, 3, 62379);
        // SAN + status checks
        Board b = Board.start();
        String[] mv = {"f2f3","e7e5","g2g4","d8h4"};
        for (String s : mv) {
            for (Move m : b.legalMoves()) if (m.toString().equals(s)) { System.out.println(b.san(m)); b.apply(m); break; }
        }
        System.out.println("Fool's mate status: " + b.status());
        // AI timing
        for (int lvl = 1; lvl <= 4; lvl++) {
            long t = System.currentTimeMillis();
            Move m = ChessAI.bestMove(Board.fromFen(kiwi), lvl);
            System.out.println("AI level " + lvl + " -> " + m + " in " + (System.currentTimeMillis() - t) + " ms");
        }
        // mate in one: white Qh5# style; back-rank mate
        Move mm = ChessAI.bestMove(Board.fromFen("6k1/5ppp/8/8/8/8/8/R3K3 w - - 0 1"), 3);
        System.out.println("Back-rank mate move: " + mm);
    }
}