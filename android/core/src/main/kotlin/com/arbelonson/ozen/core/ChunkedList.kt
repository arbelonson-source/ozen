package com.arbelonson.ozen.core

class ChunkedList<T> private constructor(
    private val chunks: Array<Array<Any?>>,
    override val size: Int,
) : AbstractList<T>(), RandomAccess {
    override fun get(index: Int): T {
        checkIndex(index)
        @Suppress("UNCHECKED_CAST")
        return chunks[index ushr SHIFT][index and MASK] as T
    }

    fun replacing(index: Int, element: T): ChunkedList<T> {
        checkIndex(index)
        val outer = chunks.copyOf()
        val chunk = outer[index ushr SHIFT].copyOf()
        chunk[index and MASK] = element
        outer[index ushr SHIFT] = chunk
        return ChunkedList(outer, size)
    }

    fun adding(element: T): ChunkedList<T> {
        val at = size and MASK
        if (at == 0) {
            val outer = Array(chunks.size + 1) { if (it < chunks.size) chunks[it] else arrayOf<Any?>(element) }
            return ChunkedList(outer, size + 1)
        }
        val outer = chunks.copyOf()
        val last = outer.size - 1
        outer[last] = outer[last].copyOf(at + 1).also { it[at] = element }
        return ChunkedList(outer, size + 1)
    }

    fun leadingSameAs(before: ChunkedList<T>): Int {
        val shorter = minOf(size, before.size)
        var chunk = 0
        while (chunk < chunks.size && chunk < before.chunks.size && chunks[chunk] === before.chunks[chunk]) chunk++
        var index = minOf(chunk shl SHIFT, shorter)
        while (index < shorter && chunks[index ushr SHIFT][index and MASK] === before.chunks[index ushr SHIFT][index and MASK]) index++
        return index
    }

    private fun checkIndex(index: Int) {
        if (index < 0 || index >= size) throw IndexOutOfBoundsException("index $index, size $size")
    }

    companion object {
        const val CHUNK = 64
        private const val SHIFT = 6
        private const val MASK = CHUNK - 1
        private val none = ChunkedList<Any?>(emptyArray(), 0)

        @Suppress("UNCHECKED_CAST")
        fun <T> empty(): ChunkedList<T> = none as ChunkedList<T>

        fun <T> of(elements: Collection<T>): ChunkedList<T> {
            if (elements.isEmpty()) return empty()
            val all = elements.toTypedArray<Any?>()
            val outer = Array((all.size + MASK) ushr SHIFT) { all.copyOfRange(it shl SHIFT, minOf((it + 1) shl SHIFT, all.size)) }
            return ChunkedList(outer, all.size)
        }
    }
}

fun <T> unchangedLeadingCount(before: List<T>, now: List<T>): Int =
    if (before is ChunkedList<T> && now is ChunkedList<T>) now.leadingSameAs(before) else 0
