import java.util.*;

/**
 * The computer player.
 *  - iterative-deepening negamax with alpha-beta pruning and a per-move time limit
 *  - transposition table (remembers positions already searched, found by Zobrist hash)
 *  - move ordering: hash move, captures (MVV-LVA), killer moves
 *  - late-move reductions (look less deeply at unpromising quiet moves)
 *  - quiescence search (keeps resolving captures so trades are not misjudged)
 *  - evaluation: material, piece-square tables, bishop pair, pawn structure, passed pawns, rooks on open files
 *  - a small opening book
 */
public class ChessAI {

    private static final int MATE = 100000, INF = 1000000;
    private static final int[] DEPTH   = {1, 2, 5, 6};                // by level 1..4
    private static final long[] MILLIS = {500, 1500, 3500, 8000};     // time cap per move
    private static final int TT_SIZE = 1 << 18, TT_MASK = TT_SIZE - 1;
    private static final byte EXACT = 1, LOWER = 2, UPPER = 3;

    private final int level, maxDepth;
    private final long deadline;
    private final Random rnd = new Random();
    private boolean stopped;
    private int nodes;

    private final long[] ttKey = new long[TT_SIZE];
    private final int[] ttScore = new int[TT_SIZE];
    private final byte[] ttDepth = new byte[TT_SIZE], ttFlag = new byte[TT_SIZE];
    private final Move[] ttMove = new Move[TT_SIZE];
    private final Move[][] killers = new Move[128][2];

    private ChessAI(int level, long maxMillis) {
        this.level = Math.max(1, Math.min(4, level));
        this.maxDepth = DEPTH[this.level - 1];
        long ms = Math.max(80, Math.min(MILLIS[this.level - 1], maxMillis));
        this.deadline = System.nanoTime() + ms * 1_000_000L;
    }

    /** Picks a move for the side to move. level: 1 (easy) .. 4 (expert). Returns null if there is no legal move. */
    public static Move bestMove(Board board, int level) { return bestMove(board, level, Long.MAX_VALUE); }

    /** Same, but never thinks longer than maxMillis (used when the clock is running low). */
    public static Move bestMove(Board board, int level, long maxMillis) {
        ChessAI ai = new ChessAI(level, maxMillis);
        Move book = ai.bookMove(board);
        return book != null ? book : ai.search(board.copyLite());
    }

    // ------------------------------------------------------------ opening book

    private static final Map<Long, List<String>> BOOK = new HashMap<>();
    static {
        String[] lines = {
            "e2e4 e7e5 g1f3 b8c6 f1b5 a7a6 b5a4 g8f6 e1g1 f8e7",
            "e2e4 e7e5 g1f3 b8c6 f1c4 f8c5 c2c3 g8f6 d2d4 e5d4",
            "e2e4 e7e5 g1f3 b8c6 d2d4 e5d4 f3d4 g8f6 d4c6 b7c6",
            "e2e4 c7c5 g1f3 d7d6 d2d4 c5d4 f3d4 g8f6 b1c3 a7a6",
            "e2e4 c7c5 g1f3 b8c6 d2d4 c5d4 f3d4 g8f6 b1c3 e7e5",
            "e2e4 e7e6 d2d4 d7d5 b1c3 g8f6 c1g5 f8e7 e4e5 f6d7",
            "e2e4 c7c6 d2d4 d7d5 b1c3 d5e4 c3e4 c8f5 e4g3 f5g6",
            "d2d4 d7d5 c2c4 e7e6 b1c3 g8f6 c1g5 f8e7 e2e3 e8g8",
            "d2d4 d7d5 c2c4 c7c6 g1f3 g8f6 b1c3 d5c4 a2a4 c8f5",
            "d2d4 g8f6 c2c4 e7e6 b1c3 f8b4 e2e3 e8g8 f1d3 d7d5",
            "d2d4 g8f6 c2c4 g7g6 b1c3 f8g7 e2e4 d7d6 g1f3 e8g8",
            "c2c4 e7e5 b1c3 g8f6 g1f3 b8c6 g2g3 d7d5 c4d5 f6d5",
            "g1f3 d7d5 g2g3 g8f6 f1g2 e7e6 e1g1 f8e7 d2d3 e8g8",
            "g1f3 g8f6 c2c4 g7g6 b1c3 f8g7 e2e4 d7d6 d2d4 e8g8"
        };
        for (String line : lines) {
            Board b = Board.start();
            for (String mv : line.split(" ")) {
                Move found = null;
                for (Move m : b.legalMoves()) if (m.toString().equals(mv)) { found = m; break; }
                if (found == null) break;                                   // ignore a bad line instead of crashing
                List<String> options = BOOK.computeIfAbsent(b.zobrist(), k -> new ArrayList<>());
                if (!options.contains(mv)) options.add(mv);
                b.apply(found);
            }
        }
    }

    private Move bookMove(Board b) {
        if (b.fullmove > 12) return null;
        List<String> options = BOOK.get(b.zobrist());
        if (options == null) return null;
        String pick = options.get(rnd.nextInt(options.size()));
        for (Move m : b.legalMoves()) if (m.toString().equals(pick)) return m;
        return null;
    }

    // ------------------------------------------------------------------ search

    private Move search(Board root) {
        List<Move> moves = root.legalMoves();
        if (moves.isEmpty()) return null;
        if (moves.size() == 1) return moves.get(0);
        order(root, moves, null, 0);
        Move best = moves.get(0);

        for (int d = 1; d <= maxDepth; d++) {
            Move iterBest = null;
            int iterScore = -INF, alpha = -INF;
            for (Move m : moves) {
                Board child = root.copyLite();
                child.apply(m);
                int score = (level == 1)
                        ? -negamax(child, d - 1, -INF, INF, 1)          // easy level: full window so we can add noise
                        : -negamax(child, d - 1, -INF, -alpha, 1);
                if (stopped) break;
                if (level == 1) score += rnd.nextInt(121) - 60;         // easy level sometimes plays a weaker move
                if (score > iterScore) { iterScore = score; iterBest = m; }
                if (score > alpha) alpha = score;
            }
            if (stopped || iterBest == null) break;                     // keep the last fully searched depth
            best = iterBest;
            moves.remove(best);
            moves.add(0, best);                                         // search the best move first next time
            if (iterScore > MATE - 100) break;                          // found a forced mate
        }
        return best;
    }

    private boolean outOfTime() {
        if ((++nodes & 1023) == 0 && (System.nanoTime() > deadline || Thread.currentThread().isInterrupted())) stopped = true;
        return stopped;
    }

    private int negamax(Board b, int depth, int alpha, int beta, int ply) {
        if (outOfTime()) return 0;
        if (b.halfmove >= 100) return 0;

        boolean check = b.inCheck(b.whiteToMove);
        List<Move> moves = null;
        if (depth <= 0) {
            if (check) depth = 1;                                        // never stop the search in the middle of a check
            else {
                moves = b.legalMoves();
                if (moves.isEmpty()) return 0;                           // stalemate
                return quiesce(b, alpha, beta, 0, moves);
            }
        }

        // transposition table lookup
        long key = b.zobrist();
        int idx = (int) (key & TT_MASK);
        int origAlpha = alpha;
        Move ttMv = null;
        if (ttFlag[idx] != 0 && ttKey[idx] == key) {
            ttMv = ttMove[idx];
            if (ttDepth[idx] >= depth && ply > 0) {
                int sc = fromTT(ttScore[idx], ply);
                if (ttFlag[idx] == EXACT) return sc;
                if (ttFlag[idx] == LOWER) alpha = Math.max(alpha, sc);
                else beta = Math.min(beta, sc);
                if (alpha >= beta) return sc;
            }
        }

        if (moves == null) moves = b.legalMoves();
        if (moves.isEmpty()) return check ? -(MATE - ply) : 0;
        order(b, moves, ttMv, ply);

        int best = -INF, i = 0;
        Move bestMv = null;
        for (Move m : moves) {
            Board child = b.copyLite();
            child.apply(m);
            boolean quiet = !isCapture(b, m) && m.promo == 0;
            int score;
            if (i >= 4 && depth >= 3 && quiet && !check) {              // late move reduction, re-search if it looks good
                score = -negamax(child, depth - 2, -alpha - 1, -alpha, ply + 1);
                if (score > alpha && !stopped) score = -negamax(child, depth - 1, -beta, -alpha, ply + 1);
            } else {
                score = -negamax(child, depth - 1, -beta, -alpha, ply + 1);
            }
            if (stopped) return 0;
            if (score > best) { best = score; bestMv = m; }
            if (score > alpha) alpha = score;
            if (alpha >= beta) {
                if (quiet && ply < killers.length) addKiller(ply, m);
                break;
            }
            i++;
        }

        byte flag = best <= origAlpha ? UPPER : (best >= beta ? LOWER : EXACT);
        if (ttFlag[idx] == 0 || ttKey[idx] != key || ttDepth[idx] <= depth) {
            ttKey[idx] = key; ttScore[idx] = toTT(best, ply); ttDepth[idx] = (byte) depth; ttFlag[idx] = flag; ttMove[idx] = bestMv;
        }
        return best;
    }

    /** Keep looking at captures until the position is "quiet", so we do not misjudge half-finished trades. */
    private int quiesce(Board b, int alpha, int beta, int qd, List<Move> moves) {
        if (outOfTime()) return 0;
        int stand = evaluate(b);
        if (stand >= beta) return stand;
        if (stand > alpha) alpha = stand;
        if (qd >= 6) return alpha;
        if (moves == null) moves = b.legalMoves();

        List<Move> tactical = new ArrayList<>();
        for (Move m : moves) if (isCapture(b, m) || m.promo == 'Q') tactical.add(m);
        order(b, tactical, null, 127);
        for (Move m : tactical) {
            Board child = b.copyLite();
            child.apply(m);
            int score = -quiesce(child, -beta, -alpha, qd + 1, null);
            if (stopped) return 0;
            if (score >= beta) return score;
            if (score > alpha) alpha = score;
        }
        return alpha;
    }

    private static int toTT(int score, int ply) {                        // mate scores are stored relative to the node
        if (score > MATE - 1000) return score + ply;
        if (score < -MATE + 1000) return score - ply;
        return score;
    }
    private static int fromTT(int score, int ply) {
        if (score > MATE - 1000) return score - ply;
        if (score < -MATE + 1000) return score + ply;
        return score;
    }

    // ----------------------------------------------------------- move ordering

    private void addKiller(int ply, Move m) {
        if (same(killers[ply][0], m)) return;
        killers[ply][1] = killers[ply][0];
        killers[ply][0] = m;
    }

    private static boolean same(Move a, Move b) {
        return a != null && b != null && a.fr == b.fr && a.fc == b.fc && a.tr == b.tr && a.tc == b.tc && a.promo == b.promo;
    }

    private static boolean isCapture(Board b, Move m) {
        return b.sq[m.tr][m.tc] != ' ' || (Character.toUpperCase(b.sq[m.fr][m.fc]) == 'P' && m.fc != m.tc);
    }

    /** Hash move first, then captures (most valuable victim, least valuable attacker), then killer moves. */
    private void order(Board b, List<Move> moves, Move ttMv, int ply) {
        int n = moves.size();
        int[] sc = new int[n];
        for (int i = 0; i < n; i++) {
            Move m = moves.get(i);
            int s = 0;
            char victim = b.sq[m.tr][m.tc], mover = b.sq[m.fr][m.fc];
            if (same(m, ttMv)) s = 2_000_000;
            else if (victim != ' ') s = 100_000 + 10 * value(victim) - value(mover) / 10;
            else if (Character.toUpperCase(mover) == 'P' && m.fc != m.tc) s = 101_000;       // en passant
            else if (ply < killers.length && same(m, killers[ply][0])) s = 90_000;
            else if (ply < killers.length && same(m, killers[ply][1])) s = 80_000;
            if (m.promo != 0) s += value(m.promo);
            sc[i] = s;
        }
        for (int i = 1; i < n; i++) {                                    // insertion sort, highest score first
            Move mv = moves.get(i);
            int s = sc[i], j = i - 1;
            while (j >= 0 && sc[j] < s) { moves.set(j + 1, moves.get(j)); sc[j + 1] = sc[j]; j--; }
            moves.set(j + 1, mv);
            sc[j + 1] = s;
        }
    }

    // -------------------------------------------------------------- evaluation

    private static int value(char p) {
        switch (Character.toUpperCase(p)) {
            case 'P': return 100;
            case 'N': return 320;
            case 'B': return 330;
            case 'R': return 500;
            case 'Q': return 900;
            default:  return 1000;
        }
    }

    private static final int[] PASSED = {0, 10, 15, 30, 55, 90};        // bonus by how far a passed pawn has advanced

    /** Score in centipawns from the point of view of the side to move. */
    private static int evaluate(Board b) {
        int score = 0, phase = 0, wBishops = 0, bBishops = 0;
        int wkR = 0, wkC = 0, bkR = 0, bkC = 0;
        int[] wPawns = new int[8], bPawns = new int[8], wMax = new int[8], bMin = new int[8];
        Arrays.fill(wMax, -1);
        Arrays.fill(bMin, 8);

        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                char p = b.sq[r][c];
                if (p == ' ') continue;
                boolean white = Board.isWhite(p);
                int sign = white ? 1 : -1;
                int idx = white ? r * 8 + c : (7 - r) * 8 + c;           // black reads the tables upside down
                switch (Character.toUpperCase(p)) {
                    case 'P':
                        score += sign * (100 + PAWN[idx]);
                        if (white) { wPawns[c]++; wMax[c] = Math.max(wMax[c], r); }
                        else       { bPawns[c]++; bMin[c] = Math.min(bMin[c], r); }
                        break;
                    case 'N': score += sign * (320 + KNIGHT[idx]); phase += 1; break;
                    case 'B': score += sign * (330 + BISHOP[idx]); phase += 1; if (white) wBishops++; else bBishops++; break;
                    case 'R': score += sign * (500 + ROOK[idx]); phase += 2; break;
                    case 'Q': score += sign * (900 + QUEEN[idx]); phase += 4; break;
                    default:
                        if (white) { wkR = r; wkC = c; } else { bkR = r; bkC = c; }
                }
            }
        }
        int[] kt = phase <= 6 ? KING_END : KING_MID;
        score += kt[wkR * 8 + wkC] - kt[(7 - bkR) * 8 + bkC];
        if (wBishops >= 2) score += 30;
        if (bBishops >= 2) score -= 30;

        for (int c = 0; c < 8; c++) {                                    // doubled and isolated pawns
            if (wPawns[c] > 1) score -= 12 * (wPawns[c] - 1);
            if (bPawns[c] > 1) score += 12 * (bPawns[c] - 1);
            boolean wIso = wPawns[c] > 0 && (c == 0 || wPawns[c - 1] == 0) && (c == 7 || wPawns[c + 1] == 0);
            boolean bIso = bPawns[c] > 0 && (c == 0 || bPawns[c - 1] == 0) && (c == 7 || bPawns[c + 1] == 0);
            if (wIso) score -= 10;
            if (bIso) score += 10;
        }

        for (int r = 0; r < 8; r++) {                                    // passed pawns and rooks on open files
            for (int c = 0; c < 8; c++) {
                char p = b.sq[r][c];
                if (p == 'P' || p == 'p') {
                    boolean white = p == 'P', passed = true;
                    for (int f = Math.max(0, c - 1); f <= Math.min(7, c + 1); f++)
                        if (white ? bMin[f] < r : wMax[f] > r) { passed = false; break; }
                    if (passed) score += white ? PASSED[6 - r] : -PASSED[r - 1];
                } else if (p == 'R') {
                    if (wPawns[c] == 0) score += bPawns[c] == 0 ? 20 : 10;
                } else if (p == 'r') {
                    if (bPawns[c] == 0) score -= wPawns[c] == 0 ? 20 : 10;
                }
            }
        }
        return b.whiteToMove ? score : -score;
    }

    // Piece-square tables, written from White's point of view (top row = rank 8).
    private static final int[] PAWN = {
         0,  0,  0,  0,  0,  0,  0,  0,
        50, 50, 50, 50, 50, 50, 50, 50,
        10, 10, 20, 30, 30, 20, 10, 10,
         5,  5, 10, 25, 25, 10,  5,  5,
         0,  0,  0, 20, 20,  0,  0,  0,
         5, -5,-10,  0,  0,-10, -5,  5,
         5, 10, 10,-20,-20, 10, 10,  5,
         0,  0,  0,  0,  0,  0,  0,  0};
    private static final int[] KNIGHT = {
       -50,-40,-30,-30,-30,-30,-40,-50,
       -40,-20,  0,  0,  0,  0,-20,-40,
       -30,  0, 10, 15, 15, 10,  0,-30,
       -30,  5, 15, 20, 20, 15,  5,-30,
       -30,  0, 15, 20, 20, 15,  0,-30,
       -30,  5, 10, 15, 15, 10,  5,-30,
       -40,-20,  0,  5,  5,  0,-20,-40,
       -50,-40,-30,-30,-30,-30,-40,-50};
    private static final int[] BISHOP = {
       -20,-10,-10,-10,-10,-10,-10,-20,
       -10,  0,  0,  0,  0,  0,  0,-10,
       -10,  0,  5, 10, 10,  5,  0,-10,
       -10,  5,  5, 10, 10,  5,  5,-10,
       -10,  0, 10, 10, 10, 10,  0,-10,
       -10, 10, 10, 10, 10, 10, 10,-10,
       -10,  5,  0,  0,  0,  0,  5,-10,
       -20,-10,-10,-10,-10,-10,-10,-20};
    private static final int[] ROOK = {
         0,  0,  0,  0,  0,  0,  0,  0,
         5, 10, 10, 10, 10, 10, 10,  5,
        -5,  0,  0,  0,  0,  0,  0, -5,
        -5,  0,  0,  0,  0,  0,  0, -5,
        -5,  0,  0,  0,  0,  0,  0, -5,
        -5,  0,  0,  0,  0,  0,  0, -5,
        -5,  0,  0,  0,  0,  0,  0, -5,
         0,  0,  0,  5,  5,  0,  0,  0};
    private static final int[] QUEEN = {
       -20,-10,-10, -5, -5,-10,-10,-20,
       -10,  0,  0,  0,  0,  0,  0,-10,
       -10,  0,  5,  5,  5,  5,  0,-10,
        -5,  0,  5,  5,  5,  5,  0, -5,
         0,  0,  5,  5,  5,  5,  0, -5,
       -10,  5,  5,  5,  5,  5,  0,-10,
       -10,  0,  5,  0,  0,  0,  0,-10,
       -20,-10,-10, -5, -5,-10,-10,-20};
    private static final int[] KING_MID = {
       -30,-40,-40,-50,-50,-40,-40,-30,
       -30,-40,-40,-50,-50,-40,-40,-30,
       -30,-40,-40,-50,-50,-40,-40,-30,
       -30,-40,-40,-50,-50,-40,-40,-30,
       -20,-30,-30,-40,-40,-30,-30,-20,
       -10,-20,-20,-20,-20,-20,-20,-10,
        20, 20,  0,  0,  0,  0, 20, 20,
        20, 30, 10,  0,  0, 10, 30, 20};
    private static final int[] KING_END = {
       -50,-40,-30,-20,-20,-30,-40,-50,
       -30,-20,-10,  0,  0,-10,-20,-30,
       -30,-10, 20, 30, 30, 20,-10,-30,
       -30,-10, 30, 40, 40, 30,-10,-30,
       -30,-10, 30, 40, 40, 30,-10,-30,
       -30,-10, 20, 30, 30, 20,-10,-30,
       -30,-30,  0,  0,  0,  0,-30,-30,
       -50,-30,-30,-30,-30,-30,-30,-50};
}