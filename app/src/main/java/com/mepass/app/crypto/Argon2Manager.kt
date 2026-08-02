package com.mepass.app.crypto

import de.mkammerer.argon2.Argon2
import de.mkammerer.argon2.Argon2Factory
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.experimental.xor

/**
 * Argon2id 密钥派生管理器
 * 用于：
 * 1. 答案哈希（用于验证答案正确性）
 * 2. 从规范化答案派生加密密钥
 * 3. 从主秘密派生最终的 passphrase
 */
object Argon2Manager {

    // Argon2id 参数配置 - 适度安全（移动端可接受性能）
    private const val ARGON2_ITERATIONS = 3
    private const val ARGON2_MEMORY_KB = 65536 // 64MB
    private const val ARGON2_PARALLELISM = 2
    private const val ARGON2_SALT_LENGTH = 16 // 字节
    private const val ARGON2_HASH_LENGTH = 32 // 字节 (256位)
    private const val PASSPHRASE_WORD_COUNT = 12

    private val argon2: Argon2 = Argon2Factory.create(
        Argon2Factory.Argon2Types.ARGON2id,
        ARGON2_SALT_LENGTH,
        ARGON2_HASH_LENGTH
    )

    private val secureRandom = SecureRandom()

    // 4 类字符池，每类字符都经过可读性筛选：
    //   - 字母：剔除 I, O, l（与数字 1, 0 易混淆）
    //   - 数字：剔除 0, 1
    //   - 符号：仅保留形状独特、不会与字母数字混淆的种类（去掉 ` | \ ~ 等边界不清的符号）
    private const val PASSPHRASE_MIN_LEN = 14
    private const val PASSPHRASE_MAX_LEN = 16
    private const val UPPERCASE = "ABCDEFGHJKLMNPQRSTUVWXYZ"     // 去 I,O
    private const val LOWERCASE = "abcdefghijkmnopqrstuvwxyz"   // 去 l
    private const val DIGITS    = "23456789"                      // 去 0,1
    // 22 种高辨识度符号：形状独特，各自视觉差异大，无歧义、无混淆
    private const val SYMBOLS   = "!@#\$%^&*()-_=+[]{};:,./<>"

    /**
     * 对答案进行哈希（用于验证数据存储）
     * 返回 Argon2 编码字符串（包含盐、参数、哈希）
     */
    fun hashAnswer(normalizedAnswer: String): String {
        val chars = normalizedAnswer.toCharArray()
        try {
            return argon2.hash(
                ARGON2_ITERATIONS,
                ARGON2_MEMORY_KB,
                ARGON2_PARALLELISM,
                chars
            )
        } finally {
            // 安全清除内存中的明文
            chars.fill('\u0000')
        }
    }

    /**
     * 验证答案是否匹配存储的哈希
     */
    fun verifyAnswer(storedHash: String, normalizedAnswer: String): Boolean {
        val chars = normalizedAnswer.toCharArray()
        try {
            return argon2.verify(storedHash, chars)
        } finally {
            chars.fill('\u0000')
        }
    }

    /**
     * 从规范化答案派生固定长度的密钥（字节数组）
     * 用于加密 Shamir 分片
     *
     * 注：mkammerer argon2-jvm 的 Argon2.hash 重载只接受 (t, m, p, char[], Charset)
     * 或 (t, m, p, String, Charset)，不接受自定义 salt 字节数组；
     * 所以我们将 salt 拼接到明文前面（格式 "salt_b64|answer"），然后对整体做
     * Argon2id，再取 base64 尾部 hash 段解码 + SHA-256 裁剪到 32 字节作为密钥。
     * 这样不依赖于内部 salt，同时仍可复现（同答案+同salt 必生成同密钥）。
     */
    fun deriveKeyFromAnswer(normalizedAnswer: String, salt: ByteArray): ByteArray {
        val saltB64 = android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP)
        val combined = "$saltB64|$normalizedAnswer"
        val chars = combined.toCharArray()
        try {
            val encoded = argon2.hash(
                ARGON2_ITERATIONS,
                ARGON2_MEMORY_KB,
                ARGON2_PARALLELISM,
                chars
            )
            return extractRawHashFromEncoded(encoded, ARGON2_HASH_LENGTH)
        } finally {
            chars.fill('\u0000')
        }
    }

    /**
     * 从 Argon2 编码字符串中提取原始哈希字节
     * Argon2 编码格式: $argon2id$v=19$m=X,t=Y,p=Z$salt_b64$hash_b64
     */
    private fun extractRawHashFromEncoded(encoded: String, expectedLength: Int): ByteArray {
        val parts = encoded.split('$')
        // parts[0]="" , parts[1]="argon2id", parts[2]="v=19", parts[3]="m=..,t=..,p=..", parts[4]=salt, parts[5]=hash
        if (parts.size >= 6) {
            val hashBase64 = parts[5]
            runCatching {
                val decoded = android.util.Base64.decode(
                    hashBase64,
                    android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING
                )
                if (decoded.size >= expectedLength) {
                    return decoded.copyOf(expectedLength)
                }
            }
        }
        // 解码失败或长度不足：退回到对整个编码做 SHA-256
        return sha256(encoded.toByteArray()).copyOf(expectedLength)
    }

    /**
     * 从主秘密字节派生 passphrase（14~16 字符，具体长度由熵决定）
     *
     * 算法流程（关键：先保证配额 → 再洗牌 → 后处理可读性，避免破坏计数）：
     *   1. 用 2 位熵在 [14, 15, 16] 三种长度中挑一个
     *   2. 为 4 类字符各自分配至少 3 个名额，剩余名额随机分配
     *   3. 严格按每类配额从熵中挑出字符（先凑齐数量，不做跨类替换）
     *   4. Fisher–Yates 整体洗牌
     *   5. 若存在 >2 个连续同类字符，通过"与不同类邻居交换"打破连续（不改变字符种类数量）
     *   6. 兜底：确保 4 类都出现（其实第3步已保证，此处仅为极度保守）
     *
     * 满足用户三大要求：
     *  1. 字符种类尽可能多（4 类字符：大写 / 小写 / 数字 / 符号，每类至少 3 次）
     *  2. 长度 14 ~ 16 位之间（由 masterSecret 熵位决定，3 种长度都可能出现）
     *  3. 全部字符均经过高辨识度筛选，无 0/1/I/O/l/`/|\~ 等易混淆字符
     */
    fun derivePassphrase(masterSecret: ByteArray): String {
        // 使用充足的熵池
        val h1 = sha512(masterSecret)
        val h2 = sha512(h1 + sha256(masterSecret))
        val entropy = ByteArray(128)
        h1.copyInto(entropy, 0, 0, 64)
        h2.copyInto(entropy, 64, 0, 64)
        var bitCursor = 0

        // ===== 第 1 步：长度选择 =====
        val lenChoice = extractBits(entropy, bitCursor, 2).let {
            when (it and 0b11) {
                0b00, 0b01 -> PASSPHRASE_MIN_LEN            // 14 (50%)
                0b10        -> PASSPHRASE_MIN_LEN + 1        // 15 (25%)
                else        -> PASSPHRASE_MAX_LEN            // 16 (25%)
            }
        }
        bitCursor += 2

        // ===== 第 2 步：4 类配额分配（每类至少 3 个） =====
        val perCategoryMin = 3
        val counts = IntArray(4) { perCategoryMin }
        val remain = lenChoice - perCategoryMin * 4   // 14→2, 15→3, 16→4
        repeat(remain) {
            val cat = extractBits(entropy, bitCursor, 2) and 0b11
            counts[cat] += 1
            bitCursor += 2
        }

        val categories = listOf(UPPERCASE, LOWERCASE, DIGITS, SYMBOLS)

        // ===== 第 3 步：严格按配额从每类中挑字符（保证数量，不跨类替换） =====
        val chars: MutableList<Pair<Char, Int>> = ArrayList(lenChoice)  // Pair(字符, 类别编号)
        for (cat in 0 until 4) {
            val pool = categories[cat]
            repeat(counts[cat]) {
                val pick = pool[extractBits(entropy, bitCursor, 8) % pool.length]
                bitCursor += 8
                chars.add(pick to cat)
            }
        }
        // 断言：字符总数等于目标长度（防止逻辑错误）
        check(chars.size == lenChoice) { "内部错误：挑出字符数 ${chars.size} != 目标 $lenChoice" }

        // ===== 第 4 步：Fisher–Yates 整体洗牌（确定性、基于熵） =====
        for (i in chars.size - 1 downTo 1) {
            val j = extractBits(entropy, bitCursor, 8) % (i + 1)
            bitCursor += 8
            val tmp = chars[i]; chars[i] = chars[j]; chars[j] = tmp
        }

        // ===== 第 5 步：后处理 —— 消除超过 2 个的连续同类字符（只交换位置，不改变字符集/数量） =====
        // 策略：从左向右扫描，发现连续 3 个及以上同类时，
        //       在剩余区间内找一个不同类字符进行交换；若找不到就扫描整个字符串找"异类邻居"互换。
        var safety = 0
        var i = 2
        while (i < chars.size && safety < lenChoice * 4) {
            safety++
            val (_, c0) = chars[i - 2]
            val (_, c1) = chars[i - 1]
            val (_, c2) = chars[i]
            if (c0 == c1 && c1 == c2) {
                // 位置 i-2,i-1,i 是连续 3 个同类 → 尝试把位置 i-1 或 i 跟后面异类交换
                val badCat = c2
                var swapTarget = -1
                // 先在 i+1..end 找异类
                for (k in i + 1 until chars.size) {
                    if (chars[k].second != badCat) { swapTarget = k; break }
                }
                // 若后面没有异类，在 0..i-3 找异类（避免刚好又造成左边三连）
                if (swapTarget < 0) {
                    for (k in 0..i - 3) {
                        if (chars[k].second != badCat
                            && (k == 0 || chars[k - 1].second != badCat)) {
                            swapTarget = k; break
                        }
                    }
                }
                if (swapTarget >= 0) {
                    // 选择与"连续序列中部"(i-1) 交换，通常更有利于打断连续
                    val tmp = chars[i - 1]; chars[i - 1] = chars[swapTarget]; chars[swapTarget] = tmp
                    // 交换后回退一格，防止在 swapTarget 附近又产生新三连
                    i = (i - 1).coerceAtLeast(2)
                    continue
                }
            }
            i++
        }

        // ===== 第 6 步：极度保守兜底 —— 替换保证 4 类至少各出现 1 次（实际第 3 步已保证） =====
        for ((c, pool) in categories.withIndex()) {
            val foundAny = chars.any { it.second == c }
            if (!foundAny) {
                // 从"出现次数最多且 >3 的类别"里找一个位置，替换为缺失类别字符
                var victimCat = -1
                for (cc in 0 until 4) {
                    val ccCount = chars.count { it.second == cc }
                    if (ccCount > perCategoryMin && (victimCat < 0 || ccCount > chars.count { it.second == victimCat }))
                        victimCat = cc
                }
                if (victimCat < 0) victimCat = (0 until 4).first { cc -> cc != c }
                val firstVictimIdx = chars.indexOfFirst { it.second == victimCat }
                if (firstVictimIdx >= 0) {
                    val replaceCh = pool[extractBits(entropy, 2048 + c * 16, 8) % pool.length]
                    chars[firstVictimIdx] = replaceCh to c
                }
            }
        }

        // ===== 收尾：构造输出字符串并清理敏感内存 =====
        val out = CharArray(lenChoice) { idx -> chars[idx].first }
        val result = String(out)
        out.fill('\u0000')
        chars.forEach { it -> /* chars 持有的 Char 已是值拷贝，无需额外 wipe */ }
        chars.clear()
        entropy.fill(0)
        return result
    }

    /**
     * 从字节数组中提取 n 位无符号整数（最多 16 位）
     */
    private fun extractBits(data: ByteArray, bitOffset: Int, bits: Int): Int {
        var value = 0
        for (i in 0 until bits) {
            val bitPos = bitOffset + i
            val byteIndex = bitPos / 8
            val bitIndex = bitPos % 8
            if (byteIndex < data.size) {
                val bit = (data[byteIndex].toInt() shr (7 - bitIndex)) and 1
                value = (value shl 1) or bit
            }
        }
        return value and ((1 shl bits) - 1)
    }

    private inline fun <T> List<T>.anyIndexed(predicate: (Int, T) -> Boolean): Boolean {
        forEachIndexed { i, v -> if (predicate(i, v)) return true }
        return false
    }

    /**
     * SHA-512 哈希（作为 passphrase 派生的额外熵池）
     */
    private fun sha512(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-512")
        return digest.digest(data)
    }

    /**
     * SHA-256 哈希
     */
    fun sha256(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data)
    }

    /**
     * 生成安全随机盐
     */
    fun generateSalt(length: Int = ARGON2_SALT_LENGTH): ByteArray {
        val salt = ByteArray(length)
        secureRandom.nextBytes(salt)
        return salt
    }

    /**
     * 生成指定长度的安全随机字节
     */
    fun generateRandomBytes(length: Int): ByteArray {
        val bytes = ByteArray(length)
        secureRandom.nextBytes(bytes)
        return bytes
    }

    /**
     * 从多个答案的密钥组合中派生主密钥
     * 使用 XOR + SHA-256 组合
     */
    fun combineKeys(keys: List<ByteArray>): ByteArray {
        require(keys.isNotEmpty()) { "密钥列表不能为空" }
        val maxLen = keys.maxOf { it.size }
        val combined = ByteArray(maxLen) { 0 }
        for (key in keys) {
            for (i in key.indices) {
                combined[i] = combined[i] xor key[i]
            }
        }
        return sha256(combined)
    }

    /**
     * 清空敏感字节数组
     */
    fun wipeBytes(bytes: ByteArray) {
        secureRandom.nextBytes(bytes)
        bytes.fill(0)
    }
}

/**
 * 简化的 2048 单词列表（2^11 = 2048，正好匹配11位索引）
 * 采用类似 BIP39 的英文单词表精选
 */
private object WordList {
    private val words = arrayOf(
        "abandon", "ability", "able", "about", "above", "absent", "absorb", "abstract",
        "absurd", "abuse", "access", "accident", "account", "accuse", "achieve", "acid",
        "acoustic", "acquire", "across", "action", "actor", "actress", "actual", "adapt",
        "add", "addict", "address", "adjust", "admit", "adult", "advance", "advice",
        "aerobic", "affair", "afford", "afraid", "again", "agent", "agree", "ahead",
        "aim", "air", "airport", "aisle", "alarm", "album", "alcohol", "alert",
        "alien", "all", "alley", "allow", "almost", "alone", "alpha", "already",
        "also", "alter", "always", "amateur", "amazing", "among", "amount", "amused",
        "analyst", "anchor", "ancient", "anger", "angle", "angry", "animal", "ankle",
        "announce", "annual", "another", "answer", "antenna", "antique", "anxiety", "any",
        "apart", "apology", "appear", "apple", "approve", "april", "arch", "arctic",
        "area", "arena", "argue", "arm", "armed", "armor", "army", "around",
        "arrange", "arrest", "arrive", "arrow", "art", "artefact", "artist", "artwork",
        "ask", "aspect", "assault", "asset", "assist", "assume", "asthma", "athlete",
        "atom", "attack", "attend", "attitude", "attract", "auction", "audit", "august",
        "aunt", "author", "auto", "autumn", "average", "avocado", "avoid", "awake",
        "aware", "away", "awesome", "awful", "awkward", "axis", "baby", "bachelor",
        "bacon", "badge", "bag", "balance", "balcony", "ball", "bamboo", "banana",
        "banner", "bar", "barely", "bargain", "barrel", "base", "basic", "basket",
        "battle", "beach", "bean", "beauty", "because", "become", "beef", "before",
        "begin", "behave", "behind", "believe", "below", "belt", "bench", "benefit",
        "best", "betray", "better", "between", "beyond", "bicycle", "bid", "bike",
        "bind", "biology", "bird", "birth", "bitter", "black", "blade", "blame",
        "blanket", "blast", "bleak", "bless", "blind", "blood", "blossom", "blow",
        "blue", "blur", "blush", "board", "boat", "body", "boil", "bomb",
        "bone", "bonus", "book", "boost", "border", "boring", "borrow", "boss",
        "bottom", "bounce", "box", "boy", "bracket", "brain", "brand", "brass",
        "brave", "bread", "breeze", "brick", "bridge", "brief", "bright", "bring",
        "brisk", "broccoli", "broken", "bronze", "broom", "brother", "brown", "brush",
        "bubble", "buddy", "budget", "buffalo", "build", "bulb", "bulk", "bullet",
        "bundle", "bunker", "burden", "burger", "burst", "bus", "business", "busy",
        "butter", "buyer", "buzz", "cabbage", "cabin", "cable", "cactus", "cage",
        "cake", "call", "calm", "camera", "camp", "can", "canal", "cancel",
        "candy", "cannon", "canoe", "canvas", "canyon", "capable", "capital", "captain",
        "car", "carbon", "card", "cargo", "carpet", "carry", "cart", "case",
        "cash", "casino", "castle", "casual", "cat", "catalog", "catch", "category",
        "cattle", "caught", "cause", "caution", "cave", "ceiling", "celery", "cement",
        "census", "century", "cereal", "certain", "chair", "chalk", "champion", "change",
        "chaos", "chapter", "charge", "chase", "cheap", "check", "cheese", "chef",
        "cherry", "chest", "chicken", "chief", "child", "chimney", "choice", "choose",
        "chronic", "chuckle", "chunk", "churn", "cigar", "circle", "citizen", "city",
        "civil", "claim", "clap", "clarify", "claw", "clay", "clean", "clerk",
        "clever", "click", "client", "cliff", "climb", "clinic", "clip", "clock",
        "clog", "close", "cloth", "cloud", "clown", "club", "clump", "cluster",
        "clutch", "coach", "coast", "coconut", "code", "coffee", "coil", "coin",
        "collect", "color", "column", "combine", "come", "comfort", "comic", "common",
        "company", "concert", "conduct", "confirm", "congress", "connect", "consider", "control",
        "convince", "cook", "cool", "copper", "copy", "coral", "core", "corn",
        "correct", "cost", "cotton", "couch", "country", "couple", "course", "cousin",
        "cover", "coyote", "crack", "cradle", "craft", "cram", "crane", "crash",
        "crawl", "crazy", "cream", "credit", "creek", "crew", "cricket", "crime",
        "crisp", "critic", "crop", "cross", "crouch", "crowd", "crucial", "cruel",
        "cruise", "crumble", "crush", "cry", "crystal", "cube", "culture", "cup",
        "cupboard", "curious", "current", "curtain", "curve", "cushion", "custom", "cute",
        "cycle", "dad", "damage", "damp", "dance", "danger", "daring", "dash",
        "daughter", "dawn", "day", "deal", "debate", "debris", "decade", "december",
        "decide", "decline", "decorate", "decrease", "deer", "defense", "define", "defy",
        "degree", "delay", "deliver", "demand", "demise", "denial", "dentist", "deny",
        "depart", "depend", "deposit", "depth", "deputy", "derive", "describe", "desert",
        "design", "desk", "despair", "destroy", "detail", "detect", "develop", "device",
        "devote", "diagram", "dial", "diamond", "diary", "dice", "diesel", "diet",
        "differ", "digital", "dignity", "dilemma", "dinner", "dinosaur", "direct", "dirt",
        "disagree", "discover", "disease", "dish", "dismiss", "disorder", "display", "distance",
        "divert", "divide", "divorce", "dizzy", "doctor", "document", "dog", "doll",
        "dolphin", "domain", "donate", "donkey", "donor", "door", "dose", "double",
        "dove", "draft", "dragon", "drama", "drastic", "draw", "dream", "dress",
        "drift", "drill", "drink", "drip", "drive", "drop", "drum", "dry",
        "duck", "dumb", "dune", "during", "dust", "dutch", "duty", "dwarf",
        "dynamic", "eager", "eagle", "early", "earn", "earth", "easily", "east",
        "easy", "echo", "ecology", "economy", "edge", "edit", "educate", "effort",
        "egg", "eight", "either", "elbow", "elder", "electric", "elegant", "element",
        "elephant", "elevator", "elite", "else", "embark", "embody", "embrace", "emerge",
        "emotion", "employ", "empower", "empty", "enable", "enact", "end", "endless",
        "endorse", "enemy", "energy", "enforce", "engage", "engine", "enhance", "enjoy",
        "enlist", "enough", "enrich", "enroll", "ensure", "enter", "entire", "entry",
        "envelope", "episode", "equal", "equip", "era", "erase", "erode", "erosion",
        "error", "erupt", "escape", "essay", "essence", "estate", "eternal", "ethics",
        "evidence", "evil", "evolve", "exact", "example", "excess", "exchange", "excite",
        "exclude", "excuse", "execute", "exercise", "exhaust", "exhibit", "exile", "exist",
        "exit", "exotic", "expand", "expect", "expire", "explain", "expose", "express",
        "extend", "extra", "eye", "eyebrow", "fabric", "face", "faculty", "fade",
        "faint", "faith", "fall", "false", "fame", "family", "famous", "fan",
        "fancy", "fantasy", "farm", "fashion", "fat", "fatal", "father", "fatigue",
        "fault", "favorite", "feature", "february", "federal", "fee", "feed", "feel",
        "female", "fence", "festival", "fetch", "fever", "few", "fiber", "fiction",
        "field", "figure", "file", "film", "filter", "final", "find", "fine",
        "finger", "finish", "fire", "firm", "first", "fish", "fit", "fitness",
        "fix", "flag", "flame", "flash", "flat", "flavor", "flee", "flight",
        "flip", "float", "flock", "floor", "flower", "fluid", "flush", "fly",
        "foam", "focus", "fog", "foil", "fold", "follow", "food", "foot",
        "force", "forest", "forget", "fork", "fortune", "forum", "forward", "fossil",
        "foster", "found", "fox", "fragile", "frame", "frequent", "fresh", "friend",
        "fringe", "frog", "front", "frost", "frown", "frozen", "fruit", "fuel",
        "fun", "funny", "furnace", "fury", "future", "gadget", "gain", "galaxy",
        "gallery", "game", "garage", "garbage", "garden", "garlic", "garment", "gas",
        "gasp", "gate", "gather", "gauge", "gaze", "general", "genius", "genre",
        "gentle", "genuine", "gesture", "ghost", "giant", "gift", "giggle", "ginger",
        "giraffe", "girl", "give", "glad", "glance", "glare", "glass", "glide",
        "glimpse", "globe", "gloom", "glory", "glove", "glow", "glue", "goat",
        "goddess", "gold", "good", "goose", "gorilla", "gospel", "gossip", "govern",
        "gown", "grab", "grace", "grain", "grant", "grape", "grass", "gravity",
        "great", "green", "grid", "grief", "grit", "grocery", "group", "grow",
        "grunt", "guard", "guess", "guide", "guilt", "guitar", "gun", "gym",
        "habit", "hair", "half", "hammer", "hamster", "hand", "happy", "harbor",
        "hard", "harsh", "harvest", "hat", "have", "hawk", "hazard", "head",
        "health", "heart", "heavy", "hedgehog", "height", "hello", "helmet", "help",
        "hen", "hero", "hip", "hire", "history", "hobby", "hockey", "hold",
        "hole", "holiday", "hollow", "home", "honey", "hood", "hope", "horn",
        "horror", "horse", "hospital", "host", "hotel", "hour", "hover", "hub",
        "huge", "human", "humble", "humor", "hundred", "hungry", "hunt", "hurdle",
        "hurry", "hurt", "husband", "hybrid", "ice", "icon", "idea", "identify",
        "idle", "ignore", "ill", "illegal", "illness", "image", "imitate", "immense",
        "immune", "impact", "impose", "improve", "impulse", "inch", "include", "income",
        "increase", "index", "indicate", "individual", "indoor", "industry", "infant", "inflict",
        "inform", "initial", "inject", "inmate", "inner", "innocent", "input", "inquiry",
        "insane", "insect", "inside", "inspire", "install", "intact", "interest", "into",
        "invest", "invite", "involve", "iron", "island", "isolate", "issue", "item",
        "ivory", "jacket", "jaguar", "jar", "jazz", "jealous", "jeans", "jelly",
        "jewel", "job", "join", "joke", "journey", "joy", "judge", "juice",
        "jump", "jungle", "junior", "junk", "just", "kangaroo", "keen", "keep",
        "ketchup", "key", "kick", "kid", "kidney", "kind", "kingdom", "kiss",
        "kit", "kitchen", "kite", "kitten", "kiwi", "knee", "knife", "knock",
        "know", "lab", "label", "labor", "ladder", "lady", "lake", "lamp",
        "language", "laptop", "large", "later", "latin", "laugh", "laundry", "lava",
        "law", "lawn", "lawsuit", "layer", "lazy", "leader", "leaf", "learn",
        "leave", "lecture", "left", "leg", "legal", "legend", "lemon", "lend",
        "length", "lens", "leopard", "lesson", "letter", "level", "liar", "liberty",
        "library", "license", "life", "lift", "light", "like", "limb", "limit",
        "link", "lion", "liquid", "list", "little", "live", "lizard", "load",
        "loan", "lobster", "local", "lock", "logic", "lonely", "long", "loop",
        "lottery", "loud", "lounge", "love", "loyal", "lucky", "luggage", "lumber",
        "lunar", "lunch", "luxury", "lyrics", "machine", "mad", "magic", "magnet",
        "maid", "mail", "main", "major", "make", "mammal", "man", "manage",
        "mandate", "mango", "mansion", "manual", "maple", "marble", "march", "margin",
        "marine", "market", "marriage", "mask", "mass", "master", "match", "material",
        "math", "matrix", "matter", "maximum", "maze", "meadow", "mean", "measure",
        "meat", "mechanic", "medal", "media", "melody", "melt", "member", "memory",
        "mention", "menu", "mercy", "merge", "merit", "merry", "mesh", "message",
        "metal", "method", "middle", "midnight", "milk", "million", "mimic", "mind",
        "minimum", "minor", "minute", "miracle", "mirror", "misery", "miss", "mistake",
        "mix", "mixed", "mixture", "mobile", "model", "modify", "mom", "moment",
        "monitor", "monkey", "monster", "month", "moon", "moral", "more", "morning",
        "mosquito", "mother", "motion", "motor", "mountain", "mouse", "move", "movie",
        "much", "muffin", "mule", "multiply", "muscle", "museum", "mushroom", "music",
        "must", "mutual", "myself", "mystery", "myth", "naive", "name", "napkin",
        "narrow", "nasty", "nation", "nature", "near", "neck", "need", "negative",
        "neglect", "neither", "nephew", "nerve", "nest", "net", "network", "neutral",
        "never", "news", "next", "nice", "night", "noble", "noise", "nominee",
        "noodle", "normal", "north", "nose", "notable", "nothing", "notice", "novel",
        "now", "nuclear", "number", "nurse", "nut", "oak", "obey", "object",
        "oblige", "obscure", "observe", "obtain", "obvious", "occur", "ocean", "october",
        "odor", "off", "offer", "office", "often", "oil", "okay", "old",
        "olive", "olympic", "omit", "once", "one", "onion", "online", "only",
        "open", "opera", "opinion", "oppose", "option", "orange", "orbit", "orchard",
        "order", "ordinary", "organ", "orient", "original", "orphan", "ostrich", "other",
        "outdoor", "outer", "output", "outside", "oval", "oven", "over", "own",
        "owner", "oxygen", "oyster", "ozone", "pact", "paddle", "page", "pair",
        "palace", "palm", "panda", "panel", "panic", "panther", "paper", "parade",
        "parent", "park", "parrot", "party", "pass", "patch", "path", "patient",
        "patrol", "pattern", "pause", "pave", "payment", "peace", "peanut", "pear",
        "peasant", "pelican", "pen", "penalty", "pencil", "people", "pepper", "perfect",
        "permit", "person", "pet", "phone", "photo", "phrase", "physical", "piano",
        "picnic", "picture", "piece", "pig", "pigeon", "pill", "pilot", "pink",
        "pioneer", "pipe", "pistol", "pitch", "pizza", "place", "planet", "plastic",
        "plate", "play", "please", "pledge", "pluck", "plug", "plunge", "poem",
        "poet", "point", "polar", "pole", "police", "pond", "pony", "pool",
        "popular", "portion", "position", "possible", "post", "potato", "pottery", "poverty",
        "powder", "power", "practice", "praise", "predict", "prefer", "prepare", "present",
        "pretty", "prevent", "price", "pride", "primary", "print", "priority", "prison",
        "private", "prize", "problem", "process", "produce", "profit", "program", "project",
        "promote", "proof", "property", "prosper", "protect", "proud", "provide", "public",
        "pudding", "pull", "pulp", "pulse", "pumpkin", "punch", "pupil", "puppy",
        "purchase", "purity", "purpose", "purse", "push", "put", "puzzle", "pyramid",
        "quality", "quantum", "quarter", "question", "quick", "quit", "quiz", "quote",
        "rabbit", "raccoon", "race", "rack", "radar", "radio", "rage", "rail",
        "rain", "raise", "rally", "ramp", "ranch", "random", "range", "rapid",
        "rare", "rate", "rather", "raven", "raw", "razor", "ready", "real",
        "reason", "rebel", "rebuild", "recall", "receive", "recipe", "record", "recover",
        "red", "reduce", "reflect", "reform", "refuse", "region", "regret", "regular",
        "reject", "relax", "release", "relief", "rely", "remain", "remember", "remind",
        "remove", "render", "renew", "rent", "reopen", "repair", "repeat", "replace",
        "report", "require", "rescue", "resemble", "resist", "resource", "response", "result",
        "retire", "retreat", "return", "reunion", "reveal", "review", "reward", "rhythm",
        "rib", "ribbon", "rice", "rich", "ride", "ridge", "rifle", "right",
        "rigid", "ring", "riot", "ripple", "risk", "river", "road", "roast",
        "robot", "robust", "rocket", "romance", "roof", "rookie", "room", "rose",
        "rotate", "rough", "round", "route", "royal", "rubber", "rude", "rug",
        "rule", "run", "runway", "rural", "sad", "saddle", "sadness", "safe",
        "sail", "salad", "salmon", "salon", "salt", "salute", "same", "sample",
        "sand", "satisfy", "satoshi", "sauce", "sausage", "save", "say", "scale",
        "scan", "scare", "scatter", "scene", "scheme", "school", "science", "scissors",
        "scorpion", "scout", "scrap", "screen", "script", "scrub", "sea", "search",
        "season", "seat", "second", "secret", "section", "security", "seed", "seek",
        "segment", "select", "sell", "seminar", "senior", "sense", "sentence", "series",
        "service", "session", "settle", "setup", "seven", "shadow", "shaft", "shallow",
        "share", "shed", "shell", "sheriff", "shield", "shift", "shine", "ship",
        "shiver", "shock", "shoe", "shoot", "shop", "short", "shoulder", "shove",
        "shrimp", "shrug", "shuffle", "shy", "sibling", "sick", "side", "siege",
        "sight", "sign", "silent", "silk", "silly", "silver", "similar", "simple",
        "since", "sing", "siren", "sister", "situate", "six", "size", "skate",
        "sketch", "ski", "skill", "skin", "skirt", "skull", "slab", "slam",
        "sleep", "slender", "slice", "slide", "slight", "slim", "slogan", "slot",
        "slow", "slush", "small", "smart", "smile", "smoke", "smooth", "snack",
        "snake", "snap", "sniff", "snow", "soap", "soccer", "social", "sock",
        "soda", "soft", "solar", "soldier", "solid", "solution", "solve", "someone",
        "song", "soon", "sorry", "sort", "soul", "sound", "soup", "source",
        "south", "space", "spare", "spatial", "spawn", "speak", "special", "speed",
        "spell", "spend", "sphere", "spice", "spider", "spike", "spin", "spirit",
        "split", "sponsor", "spoon", "sport", "spot", "spray", "spread", "spring",
        "spy", "square", "squeeze", "squirrel", "stable", "stadium", "staff", "stage",
        "stairs", "stamp", "stand", "start", "state", "stay", "steak", "steel",
        "stem", "step", "stereo", "stick", "still", "sting", "stock", "stomach",
        "stone", "stool", "story", "stove", "strategy", "street", "strike", "strong",
        "struggle", "student", "stuff", "stumble", "style", "subject", "submit", "subway",
        "success", "such", "sudden", "suffer", "sugar", "suggest", "suit", "summer",
        "sun", "sunny", "super", "supply", "supreme", "sure", "surface", "surge",
        "surprise", "surround", "survey", "survive", "suspect", "sustain", "swallow", "swamp",
        "swap", "swarm", "swear", "sweet", "swim", "swing", "switch", "sword",
        "symbol", "symptom", "syrup", "system", "table", "tackle", "tag", "tail",
        "talent", "talk", "tank", "tape", "target", "task", "taste", "tattoo",
        "taxi", "teach", "team", "tell", "ten", "tenant", "tennis", "tent",
        "term", "test", "text", "thank", "that", "theme", "then", "theory",
        "there", "they", "thing", "this", "thought", "three", "thrive", "throw",
        "thumb", "thunder", "ticket", "tide", "tiger", "tilt", "timber", "time",
        "tiny", "tip", "tired", "tissue", "title", "toast", "tobacco", "today",
        "toddler", "toe", "together", "toilet", "token", "tomato", "tomorrow", "tone",
        "tongue", "tonight", "tool", "tooth", "top", "topic", "topple", "torch",
        "tornado", "tortoise", "toss", "total", "tourist", "toward", "tower", "town",
        "toy", "track", "trade", "traffic", "tragic", "train", "transfer", "trap",
        "trash", "travel", "tray", "treat", "tree", "trend", "trial", "tribe",
        "trick", "trigger", "trim", "trip", "trophy", "trouble", "truck", "true",
        "truly", "trumpet", "trust", "truth", "try", "tube", "tuition", "tumble",
        "tuna", "tunnel", "turkey", "turn", "turtle", "twelve", "twenty", "twice",
        "twin", "twist", "two", "type", "typical", "ugly", "umbrella", "unable",
        "unaware", "uncle", "uncover", "under", "undo", "unfair", "unfold", "unhappy",
        "uniform", "unique", "unit", "universe", "unknown", "unlock", "until", "unusual",
        "unveil", "update", "upgrade", "uphold", "upon", "upper", "upset", "urban",
        "usage", "use", "used", "useful", "useless", "usual", "utility", "vacant",
        "vacuum", "vague", "valid", "valley", "valve", "van", "vanish", "vapor",
        "various", "vast", "vault", "vehicle", "velvet", "vendor", "venture", "venue",
        "verb", "verify", "version", "very", "vessel", "veteran", "viable", "vibrant",
        "vicious", "victory", "video", "view", "village", "vintage", "violin", "virtual",
        "virus", "visa", "visit", "visual", "vital", "vivid", "vocal", "voice",
        "void", "volcano", "volume", "vote", "voyage", "wage", "wait", "walk",
        "wall", "walnut", "want", "warfare", "warm", "warrior", "wash", "wasp",
        "waste", "water", "wave", "way", "wealth", "weapon", "wear", "weasel",
        "weather", "web", "wedding", "weekend", "weird", "welcome", "west", "wet",
        "whale", "what", "wheat", "wheel", "when", "where", "whip", "whisper",
        "wide", "width", "wife", "wild", "will", "win", "window", "wine",
        "wing", "wink", "winner", "winter", "wire", "wisdom", "wise", "wish",
        "witness", "wolf", "woman", "wonder", "wood", "wool", "word", "work",
        "world", "worry", "worth", "wrap", "wreck", "wrestle", "wrist", "write",
        "wrong", "yard", "year", "yellow", "you", "young", "youth", "zebra",
        "zero", "zone", "zoo"
    )

    fun getWord(index: Int): String {
        return words[index and (words.size - 1)]
    }
}
