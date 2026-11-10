import java.util.*;

/**
 * All the rules of chess, no GUI code.
 * Rows: 0 = rank 8 (top of the screen), 7 = rank 1. Upper case = White, lower case = Black, ' ' = empty.
 * Supports castling, en passant, promotion, check/checkmate/stalemate,
 * insufficient material, the 50-move rule and threefold repetition.
 */
public class Board {

    public enum Status { PLAYING, CHECKMATE, STALEMATE, DRAW }

    public final char[][] sq = new char[8][8];
    public boolean whiteToMove = true;
    public boolean wK = true, wQ = true, bK = true, bQ = true;   // castling rights
    public int epCol = -1;                                       // file of a pawn that just moved two squares, or -1
    public int halfmove = 0, fullmove = 1;
    private ArrayList<String> history;                           // positions seen; null on lightweight AI copies

    private static final int[][] KNIGHT   = {{-2,-1},{-2,1},{-1,-2},{-1,2},{1,-2},{1,2},{2,-1},{2,1}};
    private static final int[][] KING     = {{-1,-1},{-1,0},{-1,1},{0,-1},{0,1},{1,-1},{1,0},{1,1}};
    private static final int[][] DIAGONAL = {{-1,-1},{-1,1},{1,-1},{1,1}};
    private static final int[][] STRAIGHT = {{-1,0},{1,0},{0,-1},{0,1}};

    // ----------------------------------------------------------------- set-up

    public static Board start() {
        return fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
    }

    /** Builds a board from a FEN string (handy for testing and for loading positions). */
    public static Board fromFen(String fen) {
        Board b = new Board();
        String[] parts = fen.trim().split("\\s+");
        String[] rows = parts[0].split("/");
        for (int r = 0; r < 8; r++) {
            int c = 0;
            for (char ch : rows[r].toCharArray()) {
                if (Character.isDigit(ch)) for (int i = 0; i < ch - '0'; i++) b.sq[r][c++] = ' ';
                else b.sq[r][c++] = ch;
            }
        }
        b.whiteToMove = parts.length < 2 || parts[1].equals("w");
        String rights = parts.length > 2 ? parts[2] : "-";
        b.wK = rights.contains("K"); b.wQ = rights.contains("Q");
        b.bK = rights.contains("k"); b.bQ = rights.contains("q");
        b.epCol = parts.length > 3 && !parts[3].equals("-") ? parts[3].charAt(0) - 'a' : -1;
        b.halfmove = parts.length > 4 ? Integer.parseInt(parts[4]) : 0;
        b.fullmove = parts.length > 5 ? Integer.parseInt(parts[5]) : 1;
        b.history = new ArrayList<>();
        b.history.add(b.key());
        return b;
    }

    /** Full copy including the position history (used by the GUI). */
    public Board copy() {
        Board b = copyLite();
        if (history != null) b.history = new ArrayList<>(history);
        return b;
    }

    /** Fast copy without history (used inside the AI search). */
    public Board copyLite() {
        Board b = new Board();
        for (int r = 0; r < 8; r++) System.arraycopy(sq[r], 0, b.sq[r], 0, 8);
        b.whiteToMove = whiteToMove;
        b.wK = wK; b.wQ = wQ; b.bK = bK; b.bQ = bQ;
        b.epCol = epCol; b.halfmove = halfmove; b.fullmove = fullmove;
        return b;
    }

    public static boolean isWhite(char p) { return Character.isUpperCase(p); }

    // ------------------------------------------------------ FEN, hashing, validation

    /** The position as a FEN string (can be pasted into any chess program). */
    public String toFen() {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < 8; r++) {
            int empty = 0;
            for (int c = 0; c < 8; c++) {
                char p = sq[r][c];
                if (p == ' ') empty++;
                else { if (empty > 0) { sb.append(empty); empty = 0; } sb.append(p); }
            }
            if (empty > 0) sb.append(empty);
            if (r < 7) sb.append('/');
        }
        sb.append(whiteToMove ? " w " : " b ");
        String rights = (wK ? "K" : "") + (wQ ? "Q" : "") + (bK ? "k" : "") + (bQ ? "q" : "");
        sb.append(rights.isEmpty() ? "-" : rights).append(' ');
        sb.append(epCol >= 0 ? "" + (char) ('a' + epCol) + (whiteToMove ? 6 : 3) : "-");
        return sb.append(' ').append(halfmove).append(' ').append(fullmove).toString();
    }

    /** Sanity check for positions loaded from outside: one king each, no pawns on the back ranks, legal side to move. */
    public boolean isValid() {
        int wk = 0, bk = 0;
        for (int r = 0; r < 8; r++)
            for (int c = 0; c < 8; c++) {
                char p = sq[r][c];
                if (p == 'K') wk++;
                else if (p == 'k') bk++;
                else if ((p == 'P' || p == 'p') && (r == 0 || r == 7)) return false;
                else if (p != ' ' && "PNBRQpnbrq".indexOf(p) < 0) return false;
            }
        return wk == 1 && bk == 1 && !inCheck(!whiteToMove);
    }

    private static final long[][] Z_PIECE = new long[12][64];
    private static final long[] Z_CASTLE = new long[4], Z_EP = new long[8];
    private static final long Z_SIDE;
    static {
        Random rnd = new Random(0x5EEDC0DEL);
        for (long[] row : Z_PIECE) for (int i = 0; i < 64; i++) row[i] = rnd.nextLong();
        for (int i = 0; i < 4; i++) Z_CASTLE[i] = rnd.nextLong();
        for (int i = 0; i < 8; i++) Z_EP[i] = rnd.nextLong();
        Z_SIDE = rnd.nextLong();
    }

    /** 64-bit fingerprint of the position (used by the AI's transposition table and opening book). */
    public long zobrist() {
        long h = 0;
        for (int r = 0; r < 8; r++)
            for (int c = 0; c < 8; c++) {
                char p = sq[r][c];
                if (p != ' ') h ^= Z_PIECE["PNBRQKpnbrqk".indexOf(p)][r * 8 + c];
            }
        if (!whiteToMove) h ^= Z_SIDE;
        if (wK) h ^= Z_CASTLE[0];
        if (wQ) h ^= Z_CASTLE[1];
        if (bK) h ^= Z_CASTLE[2];
        if (bQ) h ^= Z_CASTLE[3];
        if (epCol >= 0) h ^= Z_EP[epCol];
        return h;
    }

    // ------------------------------------------------------------- applying a move

    /** Plays a move that is already known to be legal. */
    public void apply(Move m) {
        char p = sq[m.fr][m.fc];
        char up = Character.toUpperCase(p);
        boolean white = isWhite(p);
        boolean capture = sq[m.tr][m.tc] != ' ';

        if (up == 'P' && m.fc != m.tc && sq[m.tr][m.tc] == ' ') {     // en passant: remove the pawn that was passed
            sq[m.fr][m.tc] = ' ';
            capture = true;
        }
        sq[m.tr][m.tc] = p;
        sq[m.fr][m.fc] = ' ';
        if (m.promo != 0) sq[m.tr][m.tc] = white ? m.promo : Character.toLowerCase(m.promo);

        if (up == 'K' && Math.abs(m.tc - m.fc) == 2) {                // castling: also move the rook
            if (m.tc == 6) { sq[m.tr][5] = sq[m.tr][7]; sq[m.tr][7] = ' '; }
            else           { sq[m.tr][3] = sq[m.tr][0]; sq[m.tr][0] = ' '; }
        }

        if (up == 'K') { if (white) { wK = false; wQ = false; } else { bK = false; bQ = false; } }
        clearRookRight(m.fr, m.fc);
        clearRookRight(m.tr, m.tc);

        epCol = (up == 'P' && Math.abs(m.tr - m.fr) == 2) ? m.fc : -1;
        halfmove = (up == 'P' || capture) ? 0 : halfmove + 1;
        if (!whiteToMove) fullmove++;
        whiteToMove = !whiteToMove;
        if (history != null) history.add(key());
    }

    private void clearRookRight(int r, int c) {
        if (r == 7 && c == 0) wQ = false;
        else if (r == 7 && c == 7) wK = false;
        else if (r == 0 && c == 0) bQ = false;
        else if (r == 0 && c == 7) bK = false;
    }

    // ------------------------------------------------------------ move generation

    public List<Move> legalMoves() {
        List<Move> out = new ArrayList<>(40);
        boolean white = whiteToMove;
        for (Move m : pseudoMoves(white)) {
            Board b = copyLite();
            b.apply(m);
            if (!b.inCheck(white)) out.add(m);                        // never leave your own king attacked
        }
        return out;
    }

    private List<Move> pseudoMoves(boolean white) {
        List<Move> out = new ArrayList<>(48);
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                char p = sq[r][c];
                if (p == ' ' || isWhite(p) != white) continue;
                switch (Character.toUpperCase(p)) {
                    case 'P': pawnMoves(out, r, c, white); break;
                    case 'N': stepMoves(out, r, c, white, KNIGHT); break;
                    case 'B': slideMoves(out, r, c, white, DIAGONAL); break;
                    case 'R': slideMoves(out, r, c, white, STRAIGHT); break;
                    case 'Q': slideMoves(out, r, c, white, DIAGONAL); slideMoves(out, r, c, white, STRAIGHT); break;
                    case 'K': stepMoves(out, r, c, white, KING); castlingMoves(out, white); break;
                    default: break;
                }
            }
        }
        return out;
    }

    private void addPawnMove(List<Move> out, int fr, int fc, int tr, int tc, boolean white) {
        if (tr == (white ? 0 : 7)) for (char p : "QRBN".toCharArray()) out.add(new Move(fr, fc, tr, tc, p));
        else out.add(new Move(fr, fc, tr, tc));
    }

    private void pawnMoves(List<Move> out, int r, int c, boolean white) {
        int dir = white ? -1 : 1, startRow = white ? 6 : 1, nr = r + dir;
        if (nr < 0 || nr > 7) return;
        if (sq[nr][c] == ' ') {
            addPawnMove(out, r, c, nr, c, white);
            if (r == startRow && sq[r + 2 * dir][c] == ' ') out.add(new Move(r, c, r + 2 * dir, c));
        }
        for (int dc = -1; dc <= 1; dc += 2) {
            int nc = c + dc;
            if (nc < 0 || nc > 7) continue;
            char t = sq[nr][nc];
            if (t != ' ' && isWhite(t) != white) addPawnMove(out, r, c, nr, nc, white);
            else if (t == ' ' && epCol == nc && r == (white ? 3 : 4)) out.add(new Move(r, c, nr, nc));   // en passant
        }
    }

    private void stepMoves(List<Move> out, int r, int c, boolean white, int[][] deltas) {
        for (int[] d : deltas) {
            int nr = r + d[0], nc = c + d[1];
            if (nr < 0 || nr > 7 || nc < 0 || nc > 7) continue;
            char t = sq[nr][nc];
            if (t == ' ' || isWhite(t) != white) out.add(new Move(r, c, nr, nc));
        }
    }

    private void slideMoves(List<Move> out, int r, int c, boolean white, int[][] dirs) {
        for (int[] d : dirs) {
            int nr = r + d[0], nc = c + d[1];
            while (nr >= 0 && nr < 8 && nc >= 0 && nc < 8) {
                char t = sq[nr][nc];
                if (t == ' ') out.add(new Move(r, c, nr, nc));
                else { if (isWhite(t) != white) out.add(new Move(r, c, nr, nc)); break; }
                nr += d[0]; nc += d[1];
            }
        }
    }

    private void castlingMoves(List<Move> out, boolean white) {
        int r = white ? 7 : 0;
        char king = white ? 'K' : 'k', rook = white ? 'R' : 'r';
        if (sq[r][4] != king) return;
        boolean kingSide = white ? wK : bK, queenSide = white ? wQ : bQ;
        if (!kingSide && !queenSide) return;
        if (isAttacked(r, 4, !white)) return;                                         // cannot castle out of check
        if (kingSide && sq[r][7] == rook && sq[r][5] == ' ' && sq[r][6] == ' '
                && !isAttacked(r, 5, !white) && !isAttacked(r, 6, !white))
            out.add(new Move(r, 4, r, 6));
        if (queenSide && sq[r][0] == rook && sq[r][1] == ' ' && sq[r][2] == ' ' && sq[r][3] == ' '
                && !isAttacked(r, 3, !white) && !isAttacked(r, 2, !white))
            out.add(new Move(r, 4, r, 2));
    }

    // --------------------------------------------------------------------- check

    /** Is square (r,c) attacked by the given colour? */
    public boolean isAttacked(int r, int c, boolean byWhite) {
        int pr = byWhite ? r + 1 : r - 1;
        char pawn = byWhite ? 'P' : 'p';
        if (pr >= 0 && pr < 8) {
            if (c > 0 && sq[pr][c - 1] == pawn) return true;
            if (c < 7 && sq[pr][c + 1] == pawn) return true;
        }
        char knight = byWhite ? 'N' : 'n', king = byWhite ? 'K' : 'k';
        for (int[] d : KNIGHT) {
            int nr = r + d[0], nc = c + d[1];
            if (nr >= 0 && nr < 8 && nc >= 0 && nc < 8 && sq[nr][nc] == knight) return true;
        }
        for (int[] d : KING) {
            int nr = r + d[0], nc = c + d[1];
            if (nr >= 0 && nr < 8 && nc >= 0 && nc < 8 && sq[nr][nc] == king) return true;
        }
        char bishop = byWhite ? 'B' : 'b', rook = byWhite ? 'R' : 'r', queen = byWhite ? 'Q' : 'q';
        for (int[] d : DIAGONAL) {
            int nr = r + d[0], nc = c + d[1];
            while (nr >= 0 && nr < 8 && nc >= 0 && nc < 8) {
                char t = sq[nr][nc];
                if (t != ' ') { if (t == bishop || t == queen) return true; break; }
                nr += d[0]; nc += d[1];
            }
        }
        for (int[] d : STRAIGHT) {
            int nr = r + d[0], nc = c + d[1];
            while (nr >= 0 && nr < 8 && nc >= 0 && nc < 8) {
                char t = sq[nr][nc];
                if (t != ' ') { if (t == rook || t == queen) return true; break; }
                nr += d[0]; nc += d[1];
            }
        }
        return false;
    }

    public boolean inCheck(boolean white) {
        char king = white ? 'K' : 'k';
        for (int r = 0; r < 8; r++)
            for (int c = 0; c < 8; c++)
                if (sq[r][c] == king) return isAttacked(r, c, !white);
        return false;
    }

    // -------------------------------------------------------------- game status

    public Status status() {
        if (legalMoves().isEmpty()) return inCheck(whiteToMove) ? Status.CHECKMATE : Status.STALEMATE;
        return drawReason() != null ? Status.DRAW : Status.PLAYING;
    }

    /** Why the game is drawn (or null if it is not a draw by these rules). */
    public String drawReason() {
        if (insufficientMaterial()) return "not enough pieces to checkmate";
        if (halfmove >= 100) return "50-move rule";
        if (threefold()) return "threefold repetition";
        return null;
    }

    public boolean insufficientMaterial() {
        int minors = 0, bishopColour = -1;
        boolean knight = false, bishopsOnOneColour = true;
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                char p = Character.toUpperCase(sq[r][c]);
                if (p == ' ' || p == 'K') continue;
                if (p == 'P' || p == 'R' || p == 'Q') return false;
                minors++;
                if (p == 'N') knight = true;
                else {
                    int colour = (r + c) % 2;
                    if (bishopColour == -1) bishopColour = colour;
                    else if (bishopColour != colour) bishopsOnOneColour = false;
                }
            }
        }
        return minors <= 1 || (!knight && bishopsOnOneColour);
    }

    private boolean threefold() {
        if (history == null) return false;
        String k = history.get(history.size() - 1);
        int n = 0;
        for (String h : history) if (h.equals(k)) n++;
        return n >= 3;
    }

    private String key() {
        StringBuilder sb = new StringBuilder(80);
        for (int r = 0; r < 8; r++) sb.append(sq[r]);
        sb.append(whiteToMove ? 'w' : 'b').append(wK ? 'K' : '-').append(wQ ? 'Q' : '-')
          .append(bK ? 'k' : '-').append(bQ ? 'q' : '-').append(epCol);
        return sb.toString();
    }

    // ----------------------------------------------------------------- notation

    /** Standard algebraic notation (Nf3, exd5, O-O, e8=Q+ ...). Call BEFORE the move is applied. */
    public String san(Move m) {
        char p = sq[m.fr][m.fc];
        char up = Character.toUpperCase(p);
        String s;
        if (up == 'K' && Math.abs(m.tc - m.fc) == 2) {
            s = m.tc == 6 ? "O-O" : "O-O-O";
        } else {
            boolean capture = sq[m.tr][m.tc] != ' ' || (up == 'P' && m.fc != m.tc);
            StringBuilder sb = new StringBuilder();
            if (up == 'P') {
                if (capture) sb.append((char) ('a' + m.fc));
            } else {
                sb.append(up);
                boolean other = false, sameFile = false, sameRank = false;
                for (Move o : legalMoves()) {
                    if (o.tr == m.tr && o.tc == m.tc && sq[o.fr][o.fc] == p && !(o.fr == m.fr && o.fc == m.fc)) {
                        other = true;
                        if (o.fc == m.fc) sameFile = true;
                        if (o.fr == m.fr) sameRank = true;
                    }
                }
                if (other) {
                    if (!sameFile) sb.append((char) ('a' + m.fc));
                    else if (!sameRank) sb.append(8 - m.fr);
                    else sb.append((char) ('a' + m.fc)).append(8 - m.fr);
                }
            }
            if (capture) sb.append('x');
            sb.append((char) ('a' + m.tc)).append(8 - m.tr);
            if (m.promo != 0) sb.append('=').append(m.promo);
            s = sb.toString();
        }
        Board next = copyLite();
        next.apply(m);
        boolean check = next.inCheck(next.whiteToMove);
        if (next.legalMoves().isEmpty()) { if (check) s += "#"; }
        else if (check) s += "+";
        return s;
    }
}