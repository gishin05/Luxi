package eu.kanade.tachiyomi.ui.novel

object NovelChapterContentProvider {

    fun getContent(mangaTitle: String, chapterTitle: String): String {
        val title = mangaTitle.lowercase()
        val ch = Regex("""(?i)chapter\s*(\d+)""").find(chapterTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1

        return when {
            title.contains("mother of learning") -> getMotherOfLearning(chapterTitle, ch)
            title.contains("lord of the mysteries") -> getLordOfTheMysteries(chapterTitle, ch)
            title.contains("shadow slave") -> getShadowSlave(chapterTitle, ch)
            title.contains("beginning after the end") -> getTBATE(chapterTitle, ch)
            title.contains("omniscient reader") -> getORV(chapterTitle, ch)
            title.contains("reverend insanity") -> getReverendInsanity(chapterTitle, ch)
            else -> getRichNovelContent(mangaTitle, chapterTitle, ch)
        }
    }

    private fun getMotherOfLearning(chapter: String, ch: Int): String {
        return when (ch) {
            1 -> """
                $chapter

                Morning came, heralded by an annoying weight repeatedly bouncing on his chest.

                "Wake up, wake up, wake up!" a cheerful voice sang rhythmically into his ear.

                Zorian groaned and opened his eyes to see the beaming face of his little sister, Kirielle. Her messy brown hair was sticking out in all directions, and she was bouncing on his ribs with the kind of boundless enthusiasm that could only belong to someone who had never known the dread of final exams.

                "Go away, Kirie," he muttered, pulling the thick quilt over his head.

                "No! Mother said you have to get ready for the train to Cyoria! If you miss it, she's going to make you walk all the way there with your heavy trunk!"

                Zorian sighed, resigning himself to the cruel reality of dawn. He sat up, rubbing the sleep from his eyes, and looked around his small, tidy bedroom in Cirin. Outside the window, the autumn morning air carried the smell of damp pine needles and woodsmoke. Another school year at the Cyoria Magical Academy was about to begin.

                His third year. The year where theory gave way to real spellcraft, structured shaping, and high-level arithmancy.

                Downstairs, the smell of fried eggs and tea drifted up the staircase. Zorian quickly washed his face, threw on his academy uniform, and descended to the kitchen. His mother was bustling about, firing off instructions about proper manners and keeping up the family honor—standard rhetoric that Zorian had learned to tune out years ago.

                "Remember, Zorian," his mother reminded him sharply between flips of the pan, "your brother Fortov is already in his fourth year, and Daimen was at the top of his class before he became a renowned archmage. Don't embarrass us."

                'Always Daimen,' Zorian thought quietly. Everything was always about his prodigy brother.

                Grabbing his suitcase packed with spellbooks, notebooks, and his glass alchemical apparatus, Zorian kissed his little sister on the forehead—much to her theatrical disgust—and headed out into the cool morning toward Cirin's railway station.

                Little did he know that this ordinary morning train would soon become the starting line of an endless, supernatural loop.
            """.trimIndent()

            2 -> """
                $chapter

                The steam engine let out a sharp, piercing whistle as it chugged into the Cirin train station, billowing thick white clouds across the gravel platform.

                Zorian hauled his trunk onto the carriage, finding a quiet corner booth near the back. He liked trains. The steady rhythmic clatter of metal wheels against the iron rails had a soothing, predictable quality that let his mind wander through mathematical formulas without interruption.

                "Mind if I sit here?" a cheerful, athletic voice broke through his thoughts.

                Zorian looked up to see Taiven, a childhood friend from Cirin who was two years ahead of him at the Cyoria Academy. She was tall, fit, with short blonde hair and a confident grin, carrying a heavy duffel bag packed with martial focus gear.

                "Not at all, Taiven. Take a seat," Zorian replied, sliding his textbook closer to the window.

                "Still studying before term even starts, Zorian? You never take a break!" She laughed, slumping into the leather seat opposite him. "You know, the combat division is looking for people who actually understand warding calculations. You should think about joining the sparring clubs this semester."

                "I prefer staying in one piece," Zorian said with a wry smile. "Spells are about precision and shaping, not getting beaten with wooden staves."

                "Careful what you say, little boy, or I'll drag you to the arena on day one!"

                As the train gathered speed, rolling past golden wheat fields and dense evergreen forests of Eldemar, they talked about the academy, the notoriously harsh professors, and rumors of strange political tensions bubbling up along the borders.

                Zorian leaned back, watching the scenery blur past. Everything felt entirely normal, ordinary, and comfortable. He had no inkling that in less than thirty days, the sky above Cyoria would burn with crimson fire.
            """.trimIndent()

            3 -> """
                $chapter

                Cyoria was a city built on magic, history, and ancient secrets.

                Towering stone spires rose into the autumn sky, their rooftops decorated with silver weather vanes and lightning-catchers. The Cyoria Magical Academy occupied a massive plateau in the heart of the city, flanked by grand libraries, sprawling botanic greenhouses, and deep underground research vaults.

                Zorian walked through the academy gates, feeling the familiar hum of the ambient mana wards brushing against his skin. The sheer density of magical energy here was breathtaking.

                His first class of the semester was advanced spell shaping with Master Xvim.

                Xvim had a legendary reputation among students—a reputation for turning hopeful mages into weeping wrecks within forty-five minutes. He was a severe, middle-aged man with sharp gray eyes and an expression that permanently suggested he had smelled something offensive.

                "Take a pen and place it upon your desk," Xvim ordered in a flat, monotonous tone as the class settled in.

                Zorian took out his wooden pen and set it down.

                "Now, without using an incantation, without drawing a circle, and without moving your fingers: make the pen stand upright on its tip using pure, unshaped telekinetic mana."

                A ripple of nervous murmurs swept through the classroom. Unshaped mana manipulation was notoriously difficult; raw mana tended to disperse into heat or friction before it could exert precise force.

                Zorian narrowed his eyes, focusing his mind inward. He felt the reservoir of mana pooling beneath his sternum, drew a thin thread through his meridians, and extended it across the wooden desk.

                The pen wobbled. It rose halfway, shuddered under the uneven pressure, and clattered flat against the mahogany table.

                "Incompetent," Xvim remarked without looking up from his ledger. "Try again. And continue until your fingers bleed."

                Zorian gritted his teeth. He would master this drill, no matter how many iterations it took.
            """.trimIndent()

            4 -> """
                $chapter

                The weeks at the academy flew by in a blur of rigorous study, shaping exercises, and late nights in the third-floor library.

                It was on a chilly Tuesday evening that Zorian first noticed Zach Noveda acting strangely.

                Zach was the sole heir of the once-mighty House of Noveda, but he had always been known as an easygoing, somewhat slacking student with immense natural mana capacity and terrible study habits. Yet when Zorian passed by the courtyard, he saw Zach casually casting high-circle defensive wards that usually required a team of four battlemages.

                "Hey, Zorian!" Zach waved enthusiastically from across the cobblestone square, looking at him with a strange expression of deep familiarity—almost like an old war veteran greeting an old comrade. "Good to see you again!"

                "Again?" Zorian paused, his eyebrows furrowing. "We haven't spoken since the opening assembly."

                Zach blinked, caught himself, and scratched the back of his head with a sheepish grin. "Right! Haha, just lost in my thoughts. Busy week!"

                Later that night, Zorian couldn't shake the encounter from his mind. There was something unnatural about Zach's sudden mastery. And deeper still, in the abandoned tunnels beneath the city catacombs, city watch patrols had reported strange acoustic pulses echoing through the bedrock.

                Rumors of an ancient artifact—the Sovereign Gate—began whispering through the occult student societies. A gate capable of bending time itself, waiting for a catalyst to ignite the summer festival.
            """.trimIndent()

            5 -> """
                $chapter

                The night of the Cyoria Summer Festival arrived under a canopy of bursting fireworks and cheering crowds.

                Colored lights illuminated the canals, and street performers filled the air with floating illusions of dragons and celestial phoenixes. But amidst the celebration, an ominous shadow descended upon the city.

                Without warning, the sky turned a sickening shade of bruised violet.

                Massive red-robed cultists erupted from the sewers, backed by monstrous bone beasts and mercenary spellcasters. Screams shattered the festive atmosphere as ward towers across the city collapsed in thunderous detonations.

                Zorian rushed through the burning streets, desperately seeking shelter. High above the central plaza, a gigantic spectral gate materialized, pulsing with blinding temporal mana that bent the very fabric of reality.

                A masked figure in crimson robes hovered before the gate, facing Zach Noveda in a cataclysmic duel of high-tier destructive spells. A rogue blast of soul-rending energy rebounded off the ward barrier, striking Zorian directly in the chest.

                Everything went black. The sounds of screaming, roaring flames, and crumbling stone faded into utter silence.

                ...

                *Thump. Thump. Thump.*

                "Wake up, wake up, wake up!" a cheerful voice sang into his ear.

                Zorian's eyes snapped open. He was in his bed in Cirin. Kirielle was bouncing on his ribs. Outside the window, the morning sun was rising peacefully over the autumn trees.

                The date on the wall calendar read the first day of the semester.

                The loop had begun.
            """.trimIndent()

            else -> """
                $chapter

                Iteration #$ch of the temporal loop was underway, and Zorian was no longer the naive apprentice he had once been.

                Sitting at his desk in the early morning stillness, he closed his eyes and performed a thorough diagnostic of his soul reservoir. The mana channels within his spirit had expanded significantly from countless hours of repetition. His mastery over raw shaping was now sharp enough to carve intricate sigils into granite with a mere flicker of thought.

                "The Sovereign Gate resets physical matter and world events," Zorian murmured to himself, jotting down notes in his specialized arithmancy cipher, "but the soul retains its structural density and memories. As long as I maintain conscious control over my spirit, every loop is pure progression."

                Downstairs, Kirielle's footsteps were already pattering across the hallway.

                "Wake up, wake up!" her voice chimed, exactly as it had in every iteration before.

                Zorian smiled faintly. This time, he was prepared. He had forty-eight hours before his meeting with the Aranea mind-weavers in the Cyoria catacombs, and a hundred new shaping matrices to test.

                Taking a deep breath, he stepped out into the world, determined to unravel the mysteries of the time loop and claim true mastery over his destiny.
            """.trimIndent()
        }
    }

    private fun getShadowSlave(chapter: String, ch: Int): String {
        return when (ch) {
            1 -> """
                $chapter

                Growing up in the slums of the outskirts, Sunny had learned three fundamental truths: never trust anyone, never eat anything that doesn't smell like food, and never look up into the sky when the sirens begin to wail.

                He was sixteen years old, skin pale and thin from chronic malnutrition, wearing a tattered gray coat that had seen better decades. He had survived by being unseen, unremarkable, and small.

                Then, the Nightmare Spell found him.

                It came not with thunder or lightning, but with an icy shiver creeping up his spine, followed by a voice that whispered directly into the marrow of his bones:

                [Aspirant Sunless. Welcome to your First Nightmare.]

                The dirty alleyway dissolved into swirling mist. The cold city wind was replaced by an ancient, suffocating blizzard howling across jagged stone peaks.

                When Sunny opened his eyes, he was standing knee-deep in frozen snow, surrounded by towering monolithic stones drenched in dried, obsidian blood. Above him, a crimson moon hung suspended in the black abyss, shedding an eerie, malevolent light over the ruins.

                "Where... am I?" Sunny gasped, his breath condensing into frost.

                His heart hammered against his ribs. He looked down at his trembling hands. The runes of the Spell were already engraving themselves beneath his flesh, burning with pale luminescence.

                He had been chosen. Out of millions of sleepers, he was now cast into the crucible between apotheosis and death.
            """.trimIndent()

            2 -> """
                $chapter

                The freezing gale tore through Sunny's ragged clothes like needles of ice.

                He dragged his feet through the deep drifts, his teeth chattering uncontrollably. Every breath burned his lungs. Ahead, the silhouette of a ruined stone temple loomed against the mountain ridge, its colossal arches broken and covered in frost.

                Beneath the snow, something crunched beneath his boots.

                Sunny looked down and froze. It was not a branch or stone. It was a human skull, hollow eye sockets staring blankly toward the crimson moon. Surrounding it were hundreds more—a carpet of petrified bones stretching into the darkness.

                "A graveyard..." he whispered, his stomach turning cold.

                Suddenly, a deep, guttural growl vibrated through the stone floor of the temple.

                From the shadows of the collapsed altar, a towering beast stepped into the moonlight. It was twice the height of a man, its flesh formed of matted black fur and jagged bone armor, eyes glowing with unholy bloodlust. A dormant Nightmare Creature.

                Sunny scrambled backward, his heart pounding in his throat. He had no sword, no armor, and no magic. He had only a rusted iron dagger and his desperate will to live.

                'Think, Sunny, think!' he screamed internally. In the outskirts, when a beast cornered you, you didn't fight head-on. You found its blind spot, or you made one.
            """.trimIndent()

            else -> """
                $chapter

                The silence of the shadow realm enveloped Sunny like a second skin.

                Standing atop the ridge overlooking the dark expanse, he watched the ethereal shadows twisting around his boots, obedient to his nascent will. Every battle on the mountain had pushed him to the brink of annihilation, but with every step closer to death, his aspect grew sharper.

                [Aspect: Shadow Slave.]
                [Rank: Unknown.]
                [Flaw: Clear Conscience - You can never tell a lie.]

                Sunny grimaced at the memory of his Flaw. In a world where deception was armor and shield, being unable to speak an untruth was practically a death sentence. Yet his aspect—the power to slip into shadows, to see through darkness, and to command the remnants of fallen souls—was unlike anything the modern awakened had ever documented.

                "A slave to shadows," Sunny murmured, gripping the hilt of his weapon as the wind stirred his black hair. "Perhaps. But in this nightmare, the shadow is the one holding the blade."

                Far below, the roar of ancient awakened beasts echoed through the canyons. With a silent breath, Sunny melted into the obsidian darkness, advancing toward the heart of the trial.
            """.trimIndent()
        }
    }

    private fun getLordOfTheMysteries(chapter: String, ch: Int): String {
        return when (ch) {
            1 -> """
                $chapter

                Pain. Fierce, throbbing pain echoed inside his head like the rhythmic clang of a hammer against an anvil.

                Zhou Mingrui opened his eyes, struggling against a suffocating wave of dizziness. He found himself seated at a Victorian wooden desk in an unfamiliar, gas-lit room. The smell of burning lamp oil and sulfur hung heavy in the damp air.

                Outside the arched glass window, an impossible sight greeted him: a gigantic, eerie crimson moon hung suspended in the ink-black night sky.

                "Where... am I?" he muttered, his voice hoarse and raspy.

                On the polished desk before him lay a heavy silver revolver, its barrel faintly warm. Beside it was an open diary written in flowing Feysac script, and next to the inkwell was a circular mirror with dark silver filigree.

                Zhou Mingrui leaned closer to the mirror. The face staring back at him was not his own. It was a young man of twenty-two, with dark hair, brown eyes, and a slender scholarly build. And on his right temple was a sickening, charred gunshot wound—yet the blood had stopped flowing, and fresh flesh was knitting together before his very eyes.

                Memories flooded into his mind like a broken dam.

                Klein Moretti. Graduate of the Department of History at Tingen University. Youngest son of an imperial soldier.

                He had crossed over. Into an alternate world of steam engines, gas lamps, airships, and esoteric secrets hidden just beneath the surface of civilization.
            """.trimIndent()

            else -> """
                $chapter

                High above the clouds of the mortal world, beyond the astral plane, lay an endless expanse of grayish-white fog.

                Klein Moretti stood before the towering bronze table inside the magnificent divine palace, gazing down at the twenty-two high-backed chairs carved with mystical constellations. Here, he was not the modest history graduate scraping together pennies for his siblings' meals; he was The Fool that doesn't belong to this era.

                He extended his gloved hand and tapped the armrest of his bronze throne.

                Deep below, the spiritual prayers of Miss Justice and Mr. Hanged Man vibrated through the crimson stars, seeking knowledge, protection, and miraculous potion formulas.

                "The pathway of the Seer is one of patience, preparation, and profound observation," Klein murmured softly to himself. "To digest the potion, one must truly act."

                With a gentle flick of his wrist, the gray fog parted, revealing the intricate tapestry of fate spanning the Northern Continent. The game of deities and monsters had begun.
            """.trimIndent()
        }
    }

    private fun getTBATE(chapter: String, ch: Int): String {
        return """
            $chapter

            King Grey had lived a life defined by unparalleled martial strength, solitude, and duty. But death had stripped him of his crown, leaving behind only the cold ashes of regret.

            Warmth slowly returned to his senses. In this new world of Dicathen, he had been reborn as Arthur Leywin.

            Sitting cross-legged in his room while the household slept, he closed his eyes and began meditating, sensing the atmospheric mana particles drifting through the air—earth, fire, water, and wind.

            Drawing the luminous particles into his core, a brilliant cyan spark ignited within his chest—the awakening of a legendary mage destined to alter the fate of two continents.
        """.trimIndent()
    }

    private fun getORV(chapter: String, ch: Int): String {
        return """
            $chapter

            "There are three ways to survive in a ruined world. Now, I have forgotten a few, but one thing is certain: the fact that you who are reading this will survive."

            I looked up from my smartphone screen on the subway car of Line 3. The webnovel 'Three Ways to Survive in an Apocalypse' had just published its final epilogue. For over a decade, I, Kim Dokja, was the sole remaining reader.

            Suddenly, the subway shuddered violently and screeching sparks erupted from the rails as the lights died completely.

            In the darkness, a small horned creature with white fur floated in mid-air with a sinister grin.

            [Paid service of the 8612th planetary system is now starting.]
            [Main Scenario #1 - Proof of Value has begun.]
        """.trimIndent()
    }

    private fun getReverendInsanity(chapter: String, ch: Int): String {
        return """
            $chapter

            "Spring Autumn Cicada, activate!"

            A deafening explosion echoed through Mount Qing Mao as five hundred years of memories cascaded backwards through the River of Time.

            Fang Yuan opened his cold, pitch-black eyes. The Gu Yue Clan pavilion stood peacefully beneath the morning mist, unblemished by the wars of the future. Five hundred years in the past. Reborn.

            "No regrets," Fang Yuan murmured, staring down at his youthful hands. "In this life, I shall walk the path of true immortality, unhindered by mortal morality or fleeting worldly ties."
        """.trimIndent()
    }

    private fun getRichNovelContent(title: String, chapter: String, ch: Int): String {
        return """
            $chapter

            The morning sun filtered through the high windows, illuminating the quiet halls as chapter $ch of $title unfolded.

            Every traveler upon the great path faces trials that test the limits of courage, intellect, and endurance. With steadfast determination and a heart sharpened by experience, the journey continued forward into uncharted territory.

            Footsteps echoed against the stones as new allies and formidable rivals gathered on the horizon, ready to test the mantle of destiny.
        """.trimIndent()
    }
}
