/** One move: from-square, to-square and (for pawn promotion) the piece it becomes. Row 0 = rank 8, column 0 = file a. */
public final class Move {
    public final int fr, fc, tr, tc;
    public final char promo;            // 0 = none, otherwise 'Q', 'R', 'B' or 'N'

    public Move(int fr, int fc, int tr, int tc, char promo) {
        this.fr = fr; this.fc = fc; this.tr = tr; this.tc = tc; this.promo = promo;
    }

    public Move(int fr, int fc, int tr, int tc) { this(fr, fc, tr, tc, (char) 0); }

    /** Coordinate notation such as "e2e4" or "e7e8Q". */
    @Override public String toString() {
        return "" + (char) ('a' + fc) + (8 - fr) + (char) ('a' + tc) + (8 - tr) + (promo != 0 ? String.valueOf(promo) : "");
    }
}