package com.inbyte.imagedescriber.inference

object CategoryClassifier {

    private val keywords = mapOf(
        "animal"   to listOf("animal", "dog", "cat", "bird", "fish", "horse", "rabbit", "bear", "lion", "elephant", "duck", "butterfly", "fox", "wolf", "deer", "frog", "turtle", "snake", "pig", "cow", "sheep", "chicken", "penguin", "owl", "bee"),
        "cloud"    to listOf("cloud", "clouds", "cloudy", "overcast", "stormy", "sky", "fog", "mist", "fluffy", "gray sky", "grey sky"),
        "flower"   to listOf("flower", "flowers", "petal", "petals", "bloom", "blooming", "blossom", "rose", "daisy", "tulip", "bouquet", "garden", "sunflower", "orchid", "lily", "violet", "daffodil"),
        "home"     to listOf("house", "home", "building", "roof", "door", "window", "chimney", "wall", "cottage", "hut", "cabin", "fence", "porch", "garage", "balcony"),
        "person"   to listOf("boy", "girl", "child", "children", "person", "people", "family", "man", "woman", "figure", "figures", "kid", "kids", "human", "baby", "toddler", "mother", "father", "sister", "brother"),
        "rainbow"  to listOf("rainbow", "arc", "colorful arc", "spectrum", "multicolor", "colours", "colors", "arched"),
        "star"     to listOf("star", "stars", "starry", "night sky", "sparkle", "twinkle", "constellation", "moon", "crescent", "glowing"),
        "sun"      to listOf("sun", "sunshine", "sunlight", "sunny", "rays", "solar", "sunrise", "sunset", "bright", "shining"),
        "tree"     to listOf("tree", "trees", "trunk", "branch", "branches", "leaf", "leaves", "forest", "oak", "pine", "palm", "bush", "shrub", "bark", "roots"),
        "vehicle"  to listOf("car", "automobile", "vehicle", "sedan", "wheels", "headlight", "bumper", "windshield", "bicycle", "bike", "cycle", "pedal", "handlebar", "motorcycle", "motorbike", "scooter", "truck", "lorry", "van", "pickup", "bus", "train", "airplane", "boat", "ship"),
    )

    fun classify(description: String, minScore: Int = 1, maxResults: Int = 5): List<String> {
        val lower = description.lowercase()
        return keywords
            .mapValues { (_, kws) -> kws.count { lower.contains(it) } }
            .filter { (_, score) -> score >= minScore }
            .entries
            .sortedByDescending { it.value }
            .take(maxResults)
            .map { it.key }
    }
}
