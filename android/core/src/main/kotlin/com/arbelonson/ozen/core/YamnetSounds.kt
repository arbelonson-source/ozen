package com.arbelonson.ozen.core

object YamnetSounds {
    const val SAMPLES_PER_WINDOW = 15_600
    const val SAMPLES_BETWEEN_WINDOWS = 7_800

    val labels: Map<String, List<String>> = mapOf(
        "civil_defense_siren" to listOf("Civil defense siren"),
        "smoke_detector" to listOf("Smoke detector, smoke alarm", "Fire alarm"),
        "fire" to listOf("Fire"),
        "siren" to listOf("Siren"),
        "ambulance_siren" to listOf("Ambulance (siren)"),
        "police_siren" to listOf("Police car (siren)"),
        "fire_engine_siren" to listOf("Fire engine, fire truck (siren)"),
        "gunshot_gunfire" to listOf("Gunshot, gunfire"),
        "artillery_fire" to listOf("Artillery fire"),
        "glass_breaking" to listOf("Shatter"),
        "screaming" to listOf("Screaming"),
        "car_horn" to listOf("Vehicle horn, car horn, honking"),
        "reverse_beeps" to listOf("Reversing beeps"),
        "shout" to listOf("Shout"),
        "yell" to listOf("Yell"),
        "children_shouting" to listOf("Children shouting"),
        "crying_sobbing" to listOf("Crying, sobbing"),
        "baby_crying" to listOf("Baby cry, infant cry"),
        "door_bell" to listOf("Doorbell", "Ding-dong"),
        "knock" to listOf("Knock"),
        "telephone_bell_ringing" to listOf("Telephone bell ringing"),
        "ringtone" to listOf("Ringtone"),
        "alarm_clock" to listOf("Alarm clock"),
        "dog_bark" to listOf("Bark"),
        "dog_growl" to listOf("Growling"),
        "boiling" to listOf("Boiling"),
        "whistling" to listOf("Whistle", "Steam whistle"),
        "dog_howl" to listOf("Howl"),
        "thunder" to listOf("Thunder"),
        "thunderstorm" to listOf("Thunderstorm"),
        "fireworks" to listOf("Fireworks"),
        "firecracker" to listOf("Firecracker"),
        "door" to listOf("Door"),
        "door_slam" to listOf("Slam"),
        "telephone" to listOf("Telephone"),
        "beep" to listOf("Beep, bleep"),
        "microwave_oven" to listOf("Microwave oven"),
        "water_tap_faucet" to listOf("Water tap, faucet"),
        "dog_whimper" to listOf("Whimper (dog)"),
        "door_sliding" to listOf("Sliding door"),
        "toilet_flush" to listOf("Toilet flush"),
        "sink_filling_washing" to listOf("Sink (filling or washing)"),
        "vacuum_cleaner" to listOf("Vacuum cleaner"),
        "cat_meow" to listOf("Meow"),
        "cat" to listOf("Cat"),
        "cough" to listOf("Cough"),
        "sneeze" to listOf("Sneeze"),
        "laughter" to listOf("Laughter"),
        "applause" to listOf("Applause"),
        "music" to listOf("Music"),
    )

    fun indices(names: List<String>): Map<String, List<Int>> =
        labels.mapValues { (_, wanted) -> wanted.map(names::indexOf).filter { it >= 0 } }.filterValues { it.isNotEmpty() }

    fun candidates(scores: FloatArray, indices: Map<String, List<Int>>): List<Pair<String, Double>> =
        indices.map { (identifier, at) -> identifier to at.maxOf { scores[it].toDouble() } }
}

class SoundWindows {
    private var pending = FloatArray(0)

    fun add(chunk: FloatArray): List<FloatArray> {
        pending += chunk
        val windows = mutableListOf<FloatArray>()
        var start = 0
        while (pending.size - start >= YamnetSounds.SAMPLES_PER_WINDOW) {
            windows += pending.copyOfRange(start, start + YamnetSounds.SAMPLES_PER_WINDOW)
            start += YamnetSounds.SAMPLES_BETWEEN_WINDOWS
        }
        pending = pending.copyOfRange(start, pending.size)
        return windows
    }
}
