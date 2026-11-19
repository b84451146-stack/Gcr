package org.gcreco.tracker

// The Question class now lives in GameState.kt.
// This is your original question list, kept as it was.
object QuizBank {
    val all = listOf(
        Question(
            1, "DATA CENTRES", "Why do data centres need cooling?",
            listOf(
                "To keep servers from overheating",
                "To make the building look nice",
                "To store rain water",
                "To play music"
            ), 0,
            "Computers give off heat. Too much heat slows or damages them, so they need cooling, and some cooling systems use water."
        ),
        Question(
            2, "AI", "How can using AI be linked to water use?",
            listOf(
                "It cannot be linked at all",
                "Power plants and data-centre cooling can use water",
                "AI apps drink water",
                "Phones are filled with water"
            ), 1,
            "Making electricity and cooling servers can both use water, so heavy computing can have a water footprint."
        ),
        Question(
            3, "WATER", "Which habit saves the most water?",
            listOf(
                "Leaving the tap running while brushing",
                "Washing a car every day",
                "Turning the tap off while brushing",
                "Taking very long showers"
            ), 2,
            "A running tap wastes litres every minute. Turning it off while you brush saves water."
        ),
        Question(
            4, "FARMING", "Which watering method wastes the least water on a farm?",
            listOf(
                "Flooding the whole field",
                "Drip irrigation",
                "Spraying at noon in strong sun",
                "Watering the road"
            ), 1,
            "Drip irrigation delivers water slowly to the roots, so less is lost to evaporation and runoff."
        ),
        Question(
            5, "CLIMATE", "Which gas is the main driver of recent climate change?",
            listOf("Oxygen", "Nitrogen", "Carbon dioxide", "Helium"), 2,
            "Carbon dioxide from burning fuels traps heat in the atmosphere."
        ),
        Question(
            6, "TECH", "Which of these is a renewable energy source?",
            listOf("Coal", "Oil", "Natural gas", "Solar power"), 3,
            "Sunlight keeps coming every day, so solar power is renewable. Coal, oil and gas get used up."
        ),
        Question(
            7, "WATER", "Most of Earth's water is...",
            listOf(
                "Fresh water in rivers",
                "Salt water in the oceans",
                "Ice in your freezer",
                "Water in clouds"
            ), 1,
            "About 97 percent of Earth's water is salty ocean water, so fresh water is precious."
        ),
        Question(
            8, "AI", "Which habit makes AI use more responsible?",
            listOf(
                "Sending the same question 20 times",
                "Leaving AI apps open all night",
                "Asking clear questions and not wasting requests",
                "Always asking for the longest answer"
            ), 2,
            "Clear questions mean fewer repeated requests, which means less computing and less energy."
        ),
        Question(
            9, "FARMING", "Why do crops need water?",
            listOf(
                "To grow and make food",
                "Only to look green",
                "To make noise",
                "They do not need it"
            ), 0,
            "Plants use water to grow, carry nutrients and make food from sunlight."
        ),
        Question(
            10, "WATER", "When is a good time to water a garden?",
            listOf(
                "In the hottest part of midday",
                "Early morning or evening",
                "Only in heavy rain",
                "Never"
            ), 1,
            "In the cooler morning or evening, less water evaporates, so more reaches the plants."
        ),
        Question(
            11, "CLIMATE", "How do trees help the climate?",
            listOf(
                "They absorb carbon dioxide",
                "They make coal",
                "They block all rain",
                "They heat the air"
            ), 0,
            "Trees take in carbon dioxide as they grow and store the carbon in wood and leaves."
        ),
        Question(
            12, "TECH", "Which bulb uses less electricity for the same brightness?",
            listOf(
                "Old incandescent bulb",
                "LED bulb",
                "A candle",
                "They are all the same"
            ), 1,
            "LED bulbs make light much more efficiently than old incandescent bulbs."
        ),
        Question(
            13, "DATA CENTRES", "How can a data centre use less water?",
            listOf(
                "Efficient cooling, recycled water and clean energy",
                "Leaving taps open",
                "Running servers hotter with no checks",
                "Cooling with fire"
            ), 0,
            "Better cooling designs, reusing water and using clean power all cut down water use."
        ),
        Question(
            14, "AI", "What does this app measure?",
            listOf(
                "Every message you type",
                "How long supported AI apps were open",
                "Your screen recordings",
                "Your passwords"
            ), 1,
            "It only reads how long supported AI apps were open. It cannot see prompts or conversations."
        )
    )
}
