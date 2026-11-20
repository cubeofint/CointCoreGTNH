package coint.client;

import java.util.ArrayList;
import java.util.List;

import coint.cards.PlayingCards;

/** Layout and move previews using only the already visible cards; the server still authorizes every move. */
final class SolitaireBoard {

    static final class Card {

        final int pile, index, value, x, y, w, h, hitHeight;

        Card(int pile, int index, int value, int x, int y, int w, int h, int hitHeight) {
            this.pile = pile;
            this.index = index;
            this.value = value;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.hitHeight = hitHeight;
        }

        boolean contains(int mx, int my) {
            return mx >= x && mx < x + w && my >= y && my < y + hitHeight;
        }
    }

    final List<Card> cards = new ArrayList<>();
    final List<int[]> piles;
    final int width, height;
    final int w, h, step;
    final Card[] targets = new Card[13];
    private final int spacing, bottom;

    SolitaireBoard(List<int[]> piles, int width, int height) {
        this.piles = piles;
        this.width = width;
        this.height = height;
        spacing = Math.min(56, (width - 24) / 7);
        w = Math.min(34, spacing - 7);
        h = w * 3 / 2;
        int left = (width - spacing * 7) / 2 + (spacing - w) / 2, top = 46;
        int startY = top + h + 20, max = 1;
        for (int pile = 6; pile < 13; pile++) max = Math.max(max, piles.get(pile).length);
        step = Math.max(2, Math.min(16, (height - 53 - startY - h) / Math.max(1, max - 1)));
        bottom = height - 42;
        for (int pile = 0; pile < 13; pile++) {
            int[] values = piles.get(pile);
            int x = left + (pile < 6 ? pile < 2 ? pile : pile + 1 : pile - 6) * spacing;
            int y = pile < 6 ? top : startY;
            if (pile < 6 || values.length == 0) {
                Card card = new Card(
                    pile,
                    Math.max(0, values.length - 1),
                    values.length == 0 ? -2 : values[values.length - 1],
                    x,
                    y,
                    w,
                    h,
                    h);
                cards.add(card);
                targets[pile] = card;
            } else {
                for (int i = 0; i < values.length; i++) {
                    Card card = new Card(pile, i, values[i], x, y + i * step, w, h, i == values.length - 1 ? h : step);
                    cards.add(card);
                    targets[pile] = card;
                }
            }
        }
    }

    Card at(int mx, int my) {
        for (int i = cards.size() - 1; i >= 0; i--) if (cards.get(i)
            .contains(mx, my)) return cards.get(i);
        return null;
    }

    int targetAt(int mx, int my) {
        int padding = Math.min(8, (spacing - w) / 2);
        for (int pile = 2; pile < 13; pile++) {
            Card card = targets[pile];
            int top = pile < 6 ? card.y : card.y - card.index * step;
            int end = pile < 6 ? card.y + h : Math.max(bottom, card.y + h);
            if (mx >= card.x - padding && mx < card.x + w + padding && my >= top && my < end) return pile;
        }
        return -1;
    }

    boolean liftable(Card card) {
        if (card == null || card.pile == 0 || card.value < 0) return false;
        int[] pile = piles.get(card.pile);
        if (card.pile < 6) return card.index == pile.length - 1;
        for (int i = card.index + 1; i < pile.length; i++)
            if (pile[i] < 0 || !descending(pile[i - 1], pile[i])) return false;
        return true;
    }

    boolean canMove(int from, int first, int to) {
        if (from < 1 || from >= 13 || to < 2 || to >= 13 || from == to) return false;
        int[] source = piles.get(from), target = piles.get(to);
        if (first < 0 || first >= source.length || source[first] < 0 || from < 6 && first != source.length - 1)
            return false;
        for (int i = first + 1; i < source.length; i++)
            if (source[i] < 0 || !descending(source[i - 1], source[i])) return false;
        int card = source[first];
        if (to < 6) {
            if (first != source.length - 1) return false;
            return target.length == 0 ? PlayingCards.rank(card) == 1
                : target[target.length - 1] >= 0
                    && PlayingCards.suit(card) == PlayingCards.suit(target[target.length - 1])
                    && PlayingCards.rank(card) == PlayingCards.rank(target[target.length - 1]) + 1;
        }
        return target.length == 0 ? PlayingCards.rank(card) == 13
            : target[target.length - 1] >= 0 && descending(target[target.length - 1], card);
    }

    private static boolean descending(int above, int below) {
        return PlayingCards.red(above) != PlayingCards.red(below)
            && PlayingCards.rank(above) == PlayingCards.rank(below) + 1;
    }

    static final class Drag {

        static final long RETURN_MS = 180;
        final int pile, first, count, revision, originX, originY, offsetX, offsetY, pressX, pressY;
        final long session;
        final int[] cards;
        final boolean deselect;
        int x, y;
        boolean moved, pending;
        long returningAt = -1, pendingAt;

        Drag(SolitaireBoard board, Card card, int mx, int my, int revision, long session, boolean deselect) {
            pile = card.pile;
            first = card.index;
            cards = java.util.Arrays.copyOfRange(board.piles.get(pile), first, board.piles.get(pile).length);
            count = cards.length;
            originX = x = card.x;
            originY = y = card.y;
            offsetX = mx - x;
            offsetY = my - y;
            pressX = mx;
            pressY = my;
            this.revision = revision;
            this.session = session;
            this.deselect = deselect;
        }

        void move(int mx, int my) {
            if (pending || returningAt >= 0) return;
            moved |= Math.abs(mx - pressX) >= 3 || Math.abs(my - pressY) >= 3;
            if (moved) {
                x = mx - offsetX;
                y = my - offsetY;
            }
        }

        void returnToPile(long now) {
            pending = false;
            returningAt = now;
        }

        int renderX(long now) {
            return returningAt < 0 ? x : Math.round(x + (originX - x) * progress(now));
        }

        int renderY(long now) {
            return returningAt < 0 ? y : Math.round(y + (originY - y) * progress(now));
        }

        int spread(int original, long now) {
            int lifted = Math.max(14, original);
            return returningAt < 0 ? lifted : Math.round(lifted + (original - lifted) * progress(now));
        }

        private float progress(long now) {
            float t = Math.max(0, Math.min(1, (now - returningAt) / (float) RETURN_MS));
            return t * t * (3 - 2 * t);
        }

        boolean hides(int pile, int index) {
            return moved && this.pile == pile && index >= first;
        }
    }
}
