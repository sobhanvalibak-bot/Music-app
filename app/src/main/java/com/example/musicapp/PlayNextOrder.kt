@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example.musicapp

import androidx.media3.common.C
import androidx.media3.exoplayer.source.ShuffleOrder
import java.util.Random
import kotlin.math.min

/**
 * Shuffle order that keeps "Play next" honest.
 *
 * ExoPlayer's default shuffle order drops every newly added song at a RANDOM place, so with shuffle on
 * "Play next" could play the song ten tracks later. Here a song that is inserted right after item X in the
 * list is also placed right after X in the shuffled order, so inserting after the current song always
 * makes it the next one, shuffle or not. ("Add to queue" inserts after the item that is last in the shuffled
 * order, so it lands at the end.) A brand new list is shuffled randomly.
 */
class PlayNextShuffleOrder private constructor(
    private val order: IntArray,
    private val random: Random
) : ShuffleOrder {

    constructor(length: Int) : this(randomOrder(length, 0, Random()), Random())

    /** position[i] = place of list item i inside the shuffled order */
    private val position = IntArray(order.size).also { pos -> order.forEachIndexed { p, item -> pos[item] = p } }

    override fun getLength(): Int = order.size

    override fun getNextIndex(index: Int): Int {
        val p = position[index] + 1
        return if (p < order.size) order[p] else C.INDEX_UNSET
    }

    override fun getPreviousIndex(index: Int): Int {
        val p = position[index] - 1
        return if (p >= 0) order[p] else C.INDEX_UNSET
    }

    override fun getLastIndex(): Int = if (order.isEmpty()) C.INDEX_UNSET else order[order.size - 1]

    override fun getFirstIndex(): Int = if (order.isEmpty()) C.INDEX_UNSET else order[0]

    override fun cloneAndInsert(insertionIndex: Int, insertionCount: Int): ShuffleOrder {
        if (insertionCount <= 0) return this
        if (order.isEmpty()) return PlayNextShuffleOrder(randomOrder(insertionCount, insertionIndex, random), random)
        val shifted = IntArray(order.size) { if (order[it] >= insertionIndex) order[it] + insertionCount else order[it] }
        // shuffled place after which the new songs go
        val after = if (insertionIndex <= 0) -1 else position[min(insertionIndex, order.size) - 1]
        val result = IntArray(order.size + insertionCount)
        var w = 0
        for (p in 0..after) result[w++] = shifted[p]
        for (k in 0 until insertionCount) result[w++] = insertionIndex + k
        for (p in after + 1 until shifted.size) result[w++] = shifted[p]
        return PlayNextShuffleOrder(result, random)
    }

    override fun cloneAndRemove(indexFrom: Int, indexToExclusive: Int): ShuffleOrder {
        val count = indexToExclusive - indexFrom
        if (count <= 0) return this
        val kept = ArrayList<Int>(order.size)
        for (v in order) {
            if (v >= indexFrom && v < indexToExclusive) continue
            kept.add(if (v >= indexToExclusive) v - count else v)
        }
        return PlayNextShuffleOrder(kept.toIntArray(), random)
    }

    override fun cloneAndClear(): ShuffleOrder = PlayNextShuffleOrder(IntArray(0), random)

    private companion object {
        fun randomOrder(count: Int, offset: Int, random: Random): IntArray {
            val a = IntArray(count) { it + offset }
            for (i in count - 1 downTo 1) {
                val j = random.nextInt(i + 1)
                val t = a[i]; a[i] = a[j]; a[j] = t
            }
            return a
        }
    }
}
