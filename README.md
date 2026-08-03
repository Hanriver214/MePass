# MePass - 隐私问题门限恢复离线密码管理器

> 一款基于隐私问题 + Shamir 秘密共享 + Argon2id 的 **完全离线** 安卓密码恢复应用，助你用记忆中的隐私问题生成强密码。

[![Android CI/CD](https://github.com/Hanriver214/MePass/actions/workflows/android.yml/badge.svg)](https://github.com/Hanriver214/MePass/actions/workflows/android.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Platform: Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://www.android.com/)
[![Min SDK: 24](https://img.shields.io/badge/Min%20SDK-24-blue.svg)](https://developer.android.com/studio/releases/platforms#7.0)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9-purple.svg)](https://kotlinlang.org/)

---

## 🔐 核心设计理念

传统的「安全问题」在恢复账号时非常不安全——因为：
1. 答案明文存储在服务商，一旦泄露就全完蛋
2. 只需要回答 1-2 个问题就能绕过主密码
3. 有社工风险

**MePass 彻底解决了这些问题**：

- ✅ **模板只存问题 + 验证哈希 + 加密分片**，绝不包含答案明文
- ✅ **(k, N) 门限恢复**：N 个问题里只要正确回答 k 个就能恢复（防遗忘）
- ✅ **端到端离线运算**：应用无任何网络权限，计算全部本地完成
- ✅ **行业级加密栈**：Argon2id + Shamir 秘密共享 + AES-256-GCM

---

## ✨ 功能特性

### 🛡️ 加密与安全

| 模块 | 算法 / 实现 | 说明 |
|------|-----------|------|
| 答案派生 | **Argon2id** (m=64MB, t=3, p=2) | OWASP 推荐的抗 GPU/ASIC 攻击 KDF |
| 秘密共享 | **Shamir's Secret Sharing** (GF(2³¹-1)) | 将主秘密分为 N 份，k 份可还原 |
| 分片加密 | **AES-256-GCM** | 每个 Shamir 分片用对应问题的答案密钥加密 |
| 模板加密导出 | **Argon2id + AES-256-GCM** | 导出时可选用口令加密整个模板信封，导入时自动识别并解密 |
| 完整性 | **SHA-256** + 常量时间比较 | 模板防篡改、防时序攻击 |
| 答案规范化 | NFKC + 多日期格式统一等 | 保证「张三」=「张 三」=「zhang san？」等变体生成一致哈希 |

### 📋 12 个内置隐私问题

1. 你暗恋的人的名字
2. 你最喜欢的书的名字
3. 你被骗过多少钱（记忆最深刻的一次）
4. 你丢过多少钱（记忆最深刻的一次）
5. 你有一个最常用的密码
6. 你的坏习惯（两个字）
7. 你给自己起的真名（两个字）
8. 洗澡时通常先冲身体哪个部位（两个字）
9. 你未出生的孩子叫什么（两个字）
10. 妈妈给你的小名（两个字）
11. 你的性癖（两个字）
12. 你的隐疾（五个字）

另外**完全支持自定义问题和答案**。

---

## 🏗️ 工作原理

### 创建模板流程

```
用户选定 N 个问题 + 填写答案
       │
       ▼
  答案规范化（统一大小写/空格/日期等）
       │
       ├─► Argon2id 哈希 ──► 存入模板 verificationHashes（用于验证答案）
       │
       ▼
 生成 32 字节真随机主秘密 (CSPRNG)
       │
       ▼
 Shamir(k, N) 分割为 N 个分片
       │
       ▼
 第 i 个分片 = AES-256-GCM(用第 i 个答案 Argon2id 派生的密钥加密)
       │
       ▼
 组装成 Template（JSON 格式）:
   ├─ questions[]          只有问题文本，没有答案
   ├─ thresholdConfig      k/N 门限配置
   ├─ integrityHash        SHA-256 完整性哈希
   ├─ verificationHashes   每个问题 ID → Argon2id(答案)
   └─ shamirShares         每个问题 ID → AES(Shamir分片)
```

### 恢复密码流程

```
用户回答任意 ≥k 个问题
       │
       ▼
 答案规范化
       │
       ▼
 逐个对比 Argon2id 验证哈希
       │
       ▼
 收集正确答案对应的加密分片
       │
       ▼
 用每个答案派生的密钥 → AES-GCM 解密 → 取得 Shamir 分片
       │
       ▼
 Lagrange 插值恢复 32 字节主秘密
       │
       ▼
 Argon2id + 2048 BIP39 风格词表 → 生成 12 词 Passphrase
```

---

## 🚀 快速开始

### 构建 APK

```bash
# 克隆仓库
git clone https://github.com/Hanriver214/MePass.git && cd MePass

# 构建 Debug APK
./gradlew assembleDebug

# 构建 Release APK
./gradlew assembleRelease
```

构建产出：
- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release.apk`

### 运行单元测试

```bash
./gradlew testDebugUnitTest
```

包含三大测试套件：
- `AnswerNormalizerTest`：答案规范化逻辑（13 个用例）
- `CryptoModulesTest`：Argon2 / AES / Shamir 加密原语
- `TemplateIntegrationTest`：创建 / 篡改检测 / 恢复 / 门限 / 确定性 等端到端场景

### GitHub Actions CI

项目已配置 `.github/workflows/android.yml`：
- `push` / `PR` 触发：lint → 单测 → 构建 debug APK
- `release` 触发：自动构建 release APK 并上传到 Release Assets

---

## 📁 项目结构

```
MePass/
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml        # 明确无网络权限声明（<uses-permission> 为空）
│       │   ├── java/com/mepass/app/
│       │   │   ├── MainActivity.kt        # Jetpack Compose 入口 + 导航
│       │   │   ├── crypto/
│       │   │   │   ├── AnswerNormalizer.kt    # 答案规范化（日期/大小写/空格/全半角）
│       │   │   │   ├── Argon2Manager.kt       # Argon2id 哈希、密钥派生、2048词 Passphrase
│       │   │   │   ├── AesManager.kt          # AES-256-GCM 加解密封装
│       │   │   │   └── ShamirSecretSharing.kt # (k,N) 门限 Shamir 秘密共享
│       │   │   ├── model/
│       │   │   │   ├── DataModels.kt          # Question / Template / Threshold / RecoveryResult
│       │   │   │   └── PresetQuestions.kt     # 12 个内置问题列表
│       │   │   ├── template/
│       │   │   │   ├── IntegrityManager.kt    # SHA-256 完整性校验 + 常量时间比较
│       │   │   │   └── TemplateManager.kt     # 创建 / 导出 / 导入 / 恢复
│       │   │   └── ui/
│       │   │       ├── theme/                  # Material3 主题
│       │   │       └── screens/
│       │   │           ├── HomeScreen.kt            # 首页 + 模板概览
│       │   │           ├── CreateTemplateScreen.kt  # 创建模板（选问题+填答案+设门限）
│       │   │           ├── ImportTemplateScreen.kt  # JSON 导入 + 完整性校验
│       │   │           └── RecoverScreen.kt         # 单题即时验证 + 恢复 Passphrase
│       │   └── res/
│       └── test/java/com/mepass/app/           # 单元测试
├── .github/workflows/android.yml               # CI/CD
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

---

## ⚠️ 重要安全提示

1. **选择真正私密的问题**：不要使用在社交媒体公开过的信息（比如你的宠物名如果微博里出现过 10 次就不安全）。
2. **门限设置建议**：N=8，k=5 是不错的选择——忘掉 3 个也没事，攻击者要同时答对 5 个极难。
3. **答案规范化**：如果答案涉及日期、数字，尽量在创建和恢复时都一致；应用已自动处理常见格式。
4. **备份模板 JSON**：模板文件是恢复密码的唯一凭证，丢失则不可逆。建议多处离线备份。导出时勾选「加密导出」可用口令保护模板（Argon2id + AES-256-GCM），加密模板导入时需输入口令；明文与加密格式导入时自动识别。
5. **这不是热钱包密码管理器**：MePass 只负责「从问题 → 生成强密码」，不负责存储网站账号密码。生成的 passphrase 可作为：
   - 密码管理器主密码
   - 硬件钱包恢复密码
   - GPG 对称加密口令
   - 磁盘加密密码

---

## 📜 License

[MIT License](./LICENSE)

---

## 🤝 贡献

欢迎提交 Issue 和 PR！

本项目承诺：**永不添加网络权限、永不收集任何数据、所有计算端到端可审计**。
