package org.openrndr.kartifex.utils.regions

import io.lacuna.artifex.utils.Scalars
import org.openrndr.kartifex.*
import org.openrndr.kartifex.utils.Intersections
import org.openrndr.kartifex.utils.SweepQueue
import utils.DoubleAccumulator

object Split {
    fun split(a: Region2, b: Region2): Result {
        val queues = arrayOf<SweepQueue<Curve2>>(SweepQueue(), SweepQueue())
        addToQueue(a, queues[0])
        addToQueue(b, queues[1])
        val union = VertexUnion()
        val intersections = mutableMapOf<Curve2, DoubleAccumulator>()
        val cs = arrayOfNulls<Curve2>(2)
        while (true) {
            val idx = SweepQueue.next(*queues)
            cs[idx] = queues[idx].take()
            if (cs[idx] == null) {
                break
            }
            intersections[cs[idx]?:error("null")] = DoubleAccumulator()
            for (c in queues[1 - idx].active()) {
                cs[1 - idx] = c
                val ts = cs[0]!!.intersections(cs[1]!!)
                for (i in ts.indices) {
                    val t0: Double = ts[i].x
                    val t1: Double = ts[i].y
                    intersections[cs[0]]?.add(t0)
                    intersections[cs[1]]?.add(t1)
                    val p0: Vec2 = cs[0]!!.position(t0)
                    val p1: Vec2 = cs[1]!!.position(t1)
                    union.join(p0, p1)
                }
            }
        }
        val deduped = intersections.mapValues { (c , acc) -> dedupe(c, acc, union) }
        return Result(split(a, deduped, union), split(b, deduped, union), union.roots())
    }

    private fun split(
        region: Region2,
        splits: Map<Curve2, DoubleAccumulator>,
        union: VertexUnion
    ): Region2 = Region2(region.rings.mapNotNull { ring -> split(ring, splits, union) }.toTypedArray())

    private fun dedupe(
        c: Curve2,
        acc: DoubleAccumulator,
        union: VertexUnion
    ): DoubleAccumulator {
        val ts: DoubleArray = acc.toArray()
        ts.sort()
        val result = DoubleAccumulator()
        for (i in ts.indices) {
            val t0: Double = if (result.size() == 0) 0.0 else result.last()
            val t1 = ts[i]
            if (Scalars.equals(t0, t1, Intersections.PARAMETRIC_EPSILON)
                || Vec.equals(c.position(t0), c.position(t1), Intersections.SPATIAL_EPSILON)
            ) {
                union.join(c.position(t0), c.position(t1))
            } else if (Scalars.equals(t1, 1.0, Intersections.PARAMETRIC_EPSILON)
                || Vec.equals(c.position(t1), c.end(), Intersections.SPATIAL_EPSILON)
            ) {
                union.join(c.position(t1), c.end())
            } else {
                result.add(t1)
            }
        }
        return result
    }

    private fun split(
        r: Ring2,
        splits: Map<Curve2, DoubleAccumulator>,
        union: VertexUnion
    ): Ring2? {
        val curves: MutableList<Curve2> = mutableListOf()
        for (c in r.curves) {
            val acc: DoubleAccumulator = splits[c]!!
            for (cp in c.split(acc.toArray())) {
                val cpa = union.adjust(cp)
                if (cpa != null) {
                    curves.add(cpa)
                }
            }
        }
        return if (curves.size == 0) null else Ring2(curves)
    }

    private fun addToQueue(region: Region2, queue: SweepQueue<Curve2>) {
        for (r in region.rings) {
            for (c in r.curves) {
                // TODO EJ: determine if taking the extends of the bounding box of the curve is the better solution
                queue.add(c, c.start().x, c.end().x)
            }
        }
    }

    internal class VertexUnion {
        private val parent: MutableMap<Vec2, Vec2> = mutableMapOf()
        private val roots: MutableSet<Vec2> = mutableSetOf()
        fun join(a: Vec2, b: Vec2) {
            @Suppress("NAME_SHADOWING") var a: Vec2 = a
            @Suppress("NAME_SHADOWING") var b: Vec2 = b
            a = adjust(a)
            b = adjust(b)
            val cmp: Int = a.compareTo(b)
            when {
                cmp < 0 -> {
                    parent[b] = a
                    roots.add(a)
                }
                cmp > 0 -> {
                    parent[a] = b
                    roots.add(b)
                }
                else -> {
                    roots.add(b)
                }
            }
        }

        fun adjust(p: Vec2): Vec2 {
            var curr: Vec2 = p
            while (true) {
                val next: Vec2? = parent[curr]
                if (next == null) {
                    if (curr != p) {
                        parent[p] = curr
                    }
                    return curr
                }
                curr = next
            }
        }

        fun adjust(c: Curve2): Curve2? {
            val start: Vec2 = adjust(c.start())
            val end: Vec2 = adjust(c.end())
            return if (start == end) null else c.endpoints(start, end)
        }

        fun roots(): Set<Vec2> {
            return (roots - parent.keys)
        }
    }
    class Result(val a: Region2, val b: Region2, val splits: Set<Vec2>)
}