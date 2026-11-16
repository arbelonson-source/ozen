import Foundation

extension NumberEmphasis {
    static func spelledRanges(in text: String, profile: NumberWordProfile) -> [Range<String.Index>] {
        let words = self.words(in: text)
        let lowered: [String?] = words.map { $0.core.map(profile.normalize) }
        func joinsNext(_ index: Int, _ end: String.Index) -> Bool {
            index + 1 < words.count && end == words[index].text.endIndex && !isImmediatelyFollowedByComma(end, in: text)
        }
        func wordEnd(_ index: Int) -> String.Index { words[index].coreRange?.upperBound ?? words[index].text.endIndex }
        func isNumber(_ index: Int) -> Bool { lowered[index].map(profile.numbers.contains) ?? false }
        func hasDigit(_ index: Int) -> Bool { words[index].text.contains(where: \.isNumber) }
        func digitEnd(_ index: Int) -> String.Index { words[index].text.index(after: words[index].text.lastIndex(where: \.isNumber)!) }

        func runEnd(from start: Int) -> Int {
            var last = start
            while joinsNext(last, wordEnd(last)) {
                if isNumber(last + 1) {
                    last += 1
                } else if let word = lowered[last + 1], profile.connectors.contains(word), let previous = lowered[last],
                          profile.scales.contains(previous), last + 2 < words.count, isNumber(last + 2),
                          joinsNext(last + 1, wordEnd(last + 1)) {
                    last += 2
                } else {
                    break
                }
            }
            return last
        }

        func unit(after last: Int, _ end: String.Index, throughLinks: Bool) -> (last: Int, end: String.Index, core: String)? {
            guard joinsNext(last, end) else { return nil }
            var cursor = last
            if throughLinks {
                while joinsNext(cursor, wordEnd(cursor)), cursor + 1 < words.count, let link = lowered[cursor + 1],
                      profile.fractionLinks.contains(link) {
                    cursor += 1
                }
            }
            guard cursor + 1 < words.count, joinsNext(cursor, cursor == last ? end : wordEnd(cursor)) else { return nil }
            if words[cursor + 1].text == "₪" { return (cursor + 1, words[cursor + 1].text.endIndex, "₪") }
            guard let core = lowered[cursor + 1], profile.units.contains(core) else { return nil }
            return (cursor + 1, wordEnd(cursor + 1), core)
        }

        func repeated(after last: Int, _ end: String.Index) -> (last: Int, end: String.Index)? {
            if joinsNext(last, end), let adverb = lowered[last + 1], profile.adverbPeriods.contains(adverb) {
                return (last + 1, wordEnd(last + 1))
            }
            guard joinsNext(last, end), last + 2 < words.count, let link = lowered[last + 1], profile.repeatLinks.contains(link),
                  joinsNext(last + 1, wordEnd(last + 1))
            else { return nil }
            var counted = last + 2
            if hasDigit(counted) {
                guard joinsNext(counted, digitEnd(counted)) else { return nil }
            } else if isNumber(counted) {
                counted = runEnd(from: counted)
                guard joinsNext(counted, wordEnd(counted)) else { return nil }
            } else {
                counted -= 1
            }
            guard counted + 1 < words.count, let period = lowered[counted + 1], profile.periods.contains(period) else { return nil }
            return (counted + 1, wordEnd(counted + 1))
        }

        func mixedFraction(after last: Int, _ end: String.Index) -> (last: Int, end: String.Index)? {
            guard joinsNext(last, end), let connector = lowered[last + 1], profile.connectors.contains(connector) else { return nil }
            var cursor = last + 1
            while joinsNext(cursor, wordEnd(cursor)), cursor + 1 < words.count, let link = lowered[cursor + 1], profile.fractionLinks.contains(link) {
                cursor += 1
            }
            guard cursor + 1 < words.count, joinsNext(cursor, wordEnd(cursor)), let fraction = lowered[cursor + 1], profile.fractions.contains(fraction) else { return nil }
            return (cursor + 1, wordEnd(cursor + 1))
        }

        func numberClock(after last: Int, _ end: String.Index) -> (last: Int, end: String.Index)? {
            guard joinsNext(last, end), let link = lowered[last + 1], profile.numberClockLinks.contains(link),
                  joinsNext(last + 1, wordEnd(last + 1)), last + 2 < words.count, isNumber(last + 2),
                  let minute = lowered[last + 2], !profile.ambiguous.contains(minute)
            else { return nil }
            let second = runEnd(from: last + 2)
            let tail = finish(second, wordEnd(second), lowered[second], afterFraction: false)
            if tail.joined { return nil }
            if joinsNext(second, wordEnd(second)), let after = lowered[second + 1], profile.periods.contains(after) { return nil }
            return (second, wordEnd(second))
        }

        func finish(_ last: Int, _ end: String.Index, _ core: String?, afterFraction: Bool) -> (last: Int, end: String.Index, joined: Bool) {
            var last = last
            var end = end
            var core = core
            var joined = false
            if let tail = unit(after: last, end, throughLinks: afterFraction) {
                (last, end, core) = (tail.last, tail.end, tail.core)
                joined = true
            }
            if let core, profile.repeatUnits.contains(core), let again = repeated(after: last, end) {
                (last, end) = again
            }
            return (last, end, joined)
        }

        var result: [Range<String.Index>] = []
        var lastJoined = -1
        for (position, word) in words.enumerated() where position > lastJoined {
            var span: (start: String.Index, last: Int, end: String.Index)?
            if let firstDigit = word.text.firstIndex(where: \.isNumber), let lastDigit = word.text.lastIndex(where: \.isNumber) {
                var end = word.text.index(after: lastDigit)
                if end < word.text.endIndex, gluedUnitSigns.contains(word.text[end]) { end = word.text.index(after: end) }
                if let mixed = mixedFraction(after: position, end) {
                    let done = finish(mixed.last, mixed.end, nil, afterFraction: true)
                    span = (firstDigit, done.last, done.end)
                } else {
                    let done = finish(position, end, nil, afterFraction: false)
                    span = (firstDigit, done.last, done.end)
                }
            } else if let core = lowered[position], let start = word.coreRange?.lowerBound {
                let previous = position > 0 ? lowered[position - 1] : nil
                if profile.onceWords.contains(core) {
                    if let previous, profile.notACountBefore.contains(previous) { continue }
                    var found = repeated(after: position, wordEnd(position))
                    if found == nil, !profile.adverbPeriods.isEmpty, joinsNext(position, wordEnd(position)),
                       let one = lowered[position + 1], profile.ambiguous.contains(one) {
                        found = repeated(after: position + 1, wordEnd(position + 1))
                    }
                    guard let again = found else { continue }
                    var spanStart = start
                    if let previous, profile.ambiguous.contains(previous), joinsNext(position - 1, wordEnd(position - 1)),
                       let oneStart = words[position - 1].coreRange?.lowerBound {
                        spanStart = oneStart
                    }
                    span = (spanStart, again.last, again.end)
                } else if isNumber(position) {
                    var last = runEnd(from: position)
                    var end = wordEnd(last)
                    var afterFraction = false
                    if let mixed = mixedFraction(after: last, end) {
                        (last, end) = mixed
                        afterFraction = true
                    } else if joinsNext(last, end), let next = lowered[last + 1], profile.fractions.contains(next) {
                        last += 1
                        end = wordEnd(last)
                        afterFraction = true
                    }
                    let done = finish(last, end, lowered[last], afterFraction: afterFraction)
                    let alone = position == last
                    var spanStart = start
                    if alone, profile.plainAlone.contains(core), !done.joined,
                       !(previous.map(profile.countsAfter.contains) ?? false) { continue }
                    if alone, profile.ambiguous.contains(core) {
                        if let previous, profile.notACountBefore.contains(previous) { continue }
                        if !done.joined {
                            guard profile.oneFollowsUnit, let previous, profile.units.contains(previous),
                                  !profile.onceWords.contains(previous), joinsNext(position - 1, wordEnd(position - 1)),
                                  let unitStart = words[position - 1].coreRange?.lowerBound
                            else { continue }
                            spanStart = unitStart
                        }
                    }
                    if !afterFraction, !done.joined, !profile.ambiguous.contains(core), let clock = numberClock(after: last, end) {
                        span = (spanStart, clock.last, clock.end)
                    } else {
                        span = (spanStart, done.joined || !afterFraction ? done.last : last, done.joined || !afterFraction ? done.end : end)
                    }
                } else if profile.clockOrdinals.contains(core), position > 0, let noun = lowered[position - 1],
                          profile.clockNouns.contains(noun), joinsNext(position - 1, wordEnd(position - 1)),
                          let nounStart = words[position - 1].coreRange?.lowerBound {
                    var last = position
                    if joinsNext(last, wordEnd(last)), let next = lowered[last + 1], profile.numbers.contains(next) || profile.fractions.contains(next) {
                        last = profile.numbers.contains(next) ? runEnd(from: last + 1) : last + 1
                    }
                    span = (nounStart, last, wordEnd(last))
                } else if profile.fractions.contains(core) {
                    if joinsNext(position, wordEnd(position)), let link = lowered[position + 1], profile.clockLinks.contains(link),
                       joinsNext(position + 1, wordEnd(position + 1)), position + 2 < words.count, isNumber(position + 2) || hasDigit(position + 2) {
                        let last = hasDigit(position + 2) ? position + 2 : runEnd(from: position + 2)
                        let done = finish(last, hasDigit(last) ? digitEnd(last) : wordEnd(last), nil, afterFraction: false)
                        span = (start, done.last, done.end)
                    } else {
                        let done = finish(position, wordEnd(position), nil, afterFraction: true)
                        if done.joined { span = (start, done.last, done.end) }
                    }
                }
            }
            guard let span else { continue }
            result.append(span.start..<span.end)
            lastJoined = span.last
        }
        return result
    }

    static func spelledListable(_ text: String, profile: NumberWordProfile) -> Bool {
        spelledRanges(in: text, profile: profile).contains { range in
            let found = text[range]
            if found.contains(where: \.isNumber) { return true }
            if found.contains(where: { $0.isWhitespace || $0 == "-" }) { return true }
            return !profile.idioms.contains(found.lowercased())
        }
    }
}
