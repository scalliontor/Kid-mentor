# Sanitizer V2 — Comprehensive Test Cases

Run on server:
```bash
python scripts/test_sanitizer.py
```

Or test a single string:
```bash
python scripts/test_sanitizer.py "your text here"
```

---

## 1. LaTeX Math Expressions

| # | Input | Expected | Rule |
|---|-------|----------|------|
| 1.1 | `$x^2 + 3x - 2 = 0$` | `x bình cộng 3 x trừ 2 bằng 0` | Strip `$`, `^2` → bình |
| 1.2 | `$x^3 - 8 = 0$` | `x khối trừ 8 bằng 0` | `^3` → khối |
| 1.3 | `$a^{2n}$` | `a mũ 2n` | `^{...}` → mũ |
| 1.4 | `$\frac{a+b}{c}$` | `a cộng b chia c` | `\frac{}{}` → chia |
| 1.5 | `$\sqrt{x+1}$` | `căn x cộng 1` | `\sqrt{}` → căn |
| 1.6 | `$\sqrt{4}$` | `căn 4` | simple sqrt |
| 1.7 | `$a \times b = c$` | `a nhân b bằng c` | `\times` → nhân |
| 1.8 | `$a \cdot b$` | `a nhân b` | `\cdot` → nhân |
| 1.9 | `$x \leq 5$` | `x nhỏ hơn hoặc bằng 5` | `\leq` |
| 1.10 | `$x \geq 0$` | `x lớn hơn hoặc bằng 0` | `\geq` |
| 1.11 | `$\pi \approx 3.14$` | `pi xấp xỉ 3.14` | `\approx` |
| 1.12 | `$x = \frac{-b \pm \sqrt{b^2 - 4ac}}{2a}$` | `x bằng âm b cộng trừ căn b bình trừ 4 a c chia 2 a` | Full quadratic formula |
| 1.13 | `$\Delta = b^2 - 4ac$` | `biệt thức bằng b bình trừ 4 a c` | `\Delta` → biệt thức |
| 1.14 | `$$x^2 + 1 = 0$$` | `x bình cộng 1 bằng 0` | Display math `$$` |
| 1.15 | `$3x - 2 = 0$` | `3 x trừ 2 bằng 0` | Variable math in LaTeX |

## 2. Markdown Formatting

| # | Input | Expected | Rule |
|---|-------|----------|------|
| 2.1 | `**in đậm**` | `in đậm` | Bold `**...**` stripped |
| 2.2 | `*in nghiêng*` | `in nghiêng` | Italic `*...*` stripped |
| 2.3 | `***cả hai***` | `cả hai` | Bold+italic stripped |
| 2.4 | `đây là **từ khóa** quan trọng` | `đây là từ khóa quan trọng` | Inline bold |
| 2.5 | `*Lục Vân Tiên*` | `Lục Vân Tiên` | Vietnamese italic — must NOT become "nhân Lục Vân Tiên nhân" |
| 2.6 | `**Lục Vân Tiên**` | `Lục Vân Tiên` | Vietnamese bold |
| 2.7 | `# Tiêu đề` | `Tiêu đề` | `#` stripped by `[*_#`]` |
| 2.8 | `điểm **A** và **B**` | `điểm A và B` | Multiple bold segments |

## 3. HTML Tags

| # | Input | Expected | Rule |
|---|-------|----------|------|
| 3.1 | `<b>in đậm</b>` | `in đậm` | HTML tags stripped |
| 3.2 | `<br>xin chào` | `xin chào` | `<br>` stripped |
| 3.3 | `<p>đoạn văn</p>` | `đoạn văn` | `<p>` stripped |

## 4. Emoji

| # | Input | Expected | Rule |
|---|-------|----------|------|
| 4.1 | `xin chào 👋` | `xin chào` | Emoji removed |
| 4.2 | `tuyệt vời 🎉🎉🎉` | `tuyệt vời` | Multiple emoji |
| 4.3 | `😀 vui quá` | `vui quá` | Leading emoji |

## 5. Whitespace & Structure

| # | Input | Expected | Rule |
|---|-------|----------|------|
| 5.1 | `dòng 1\ndòng 2` | `dòng 1 . dòng 2` | Newline → " . " |
| 5.2 | `dòng 1\r\ndòng 2` | `dòng 1 . dòng 2` | CRLF → " . " |
| 5.3 | `khoảng   trắng` | `khoảng trắng` | Multi-space collapse |
| 5.4 | `---` | `. ` | Triple dash → period |
| 5.5 | `đoạn — này` | `đoạn . này` | Em dash U+2014 → pause |
| 5.6 | `đoạn – này` | `đoạn . này` | En dash U+2013 → pause |
| 5.7 | `Tuy nhiên, tớ là Lisa — một trợ lý` | `Tuy nhiên, tớ là Lisa . một trợ lý` | Em dash in sentence |

## 6. Chemical Formulas

| # | Input | Expected | Rule |
|---|-------|----------|------|
| 6.1 | `H2O` | `H hai O` | Basic formula |
| 6.2 | `CO2` | `C O hai` | Carbon dioxide |
| 6.3 | `CaCO3` | `Ca C O ba` | Calcium carbonate |
| 6.4 | `Fe2O3` | `Fe hai O ba` | Iron oxide |
| 6.5 | `H2SO4` | `H hai S O bốn` | Sulfuric acid |
| 6.6 | `NaOH` | `NaOH` | No digits → unchanged |
| 6.7 | `NaCl` | `NaCl` | No digits → unchanged |
| 6.8 | `O2` | `O hai` | Oxygen |
| 6.9 | `N2` | `N hai` | Nitrogen |
| 6.10 | `Ca(OH)2` | `Ca(OH) hai` | Parenthetical formula — digits after `)` |

## 7. Chemical Equation Arrows

| # | Input | Expected | Rule |
|---|-------|----------|------|
| 7.1 | `2H2 + O2 -> 2H2O` | `2 H hai cộng O hai cho ra 2 H hai O` | Arrow `->` |
| 7.2 | `NaOH + HCl → NaCl + H2O` | `NaOH cộng HCl cho ra NaCl cộng H hai O` | Unicode arrow `→` |
| 7.3 | `CaCO3 -> CaO + CO2` | `Ca C O ba cho ra Ca O cộng C O hai` | Decomposition |

## 8. Hyphen Handling

### 8a. Math Subtraction (digit - digit)

| # | Input | Expected | Rule |
|---|-------|----------|------|
| 8.1 | `3 - 2 = 1` | `3 trừ 2 bằng 1` | Pure digit subtraction |
| 8.2 | `10 - 5` | `10 trừ 5` | Multi-digit |
| 8.3 | `$3x - 2 = 0$` | `3 x trừ 2 bằng 0` | Subtraction in LaTeX |

### 8b. Variable Math (letter - digit)

| # | Input | Expected | Rule |
|---|-------|----------|------|
| 8.4 | `x - 3 lớn hơn 0` | `x trừ 3 lớn hơn 0` | Variable minus digit |
| 8.5 | `a - 5` | `a trừ 5` | Single letter minus digit |

### 8c. Natural Text Ranges (word - word)

| # | Input | Expected | Rule |
|---|-------|----------|------|
| 8.6 | `tháng 8 - tháng 10` | `tháng 8 đến tháng 10` | Date range |
| 8.7 | `Động Ngườm - Ao Thủy Tiên` | `Động Ngườm đến Ao Thủy Tiên` | Place name range |
| 8.8 | `150-200 từ` | `150-200 từ` | No-space range → unchanged |

### 8d. English Compound Adjectives (MUST NOT break)

| # | Input | Expected | Rule |
|---|-------|----------|------|
| 8.9 | `cutting-edge` | `cutting-edge` | Hyphenated word preserved |
| 8.10 | `well-known` | `well-known` | Hyphenated word preserved |
| 8.11 | `high-quality` | `high-quality` | Hyphenated word preserved |
| 8.12 | `PTIT is well-known for its high-quality training` | `PTIT is well-known for its high-quality training` | Full sentence with hyphens |

## 9. Unit Notation

| # | Input | Expected | Rule |
|---|-------|----------|------|
| 9.1 | `km/h` | `ki-lô-mét trên giờ` | km/h unit |
| 9.2 | `m/s` | `mét trên giây` | m/s unit |
| 9.3 | `m/s2` | `mét trên giây bình` | Acceleration unit |
| 9.4 | `kg/m3` | `ki-lô-gam trên mét khối` | Density unit |
| 9.5 | `N/m2` | `niu-tơn trên mét vuông` | Pressure unit |
| 9.6 | `J/kg` | `jun trên ki-lô-gam` | Specific energy |
| 9.7 | `kWh` | `ki-lô-oát-giờ` | Energy unit |
| 9.8 | `oC` | `độ xê` | Celsius (ASCII) |
| 9.9 | `°C` | `độ xê` | Celsius (Unicode) |
| 9.10 | `v = s/t` | `v bằng s chia t` | Single-letter vars → "chia" (not unit) |
| 9.11 | `mét/giờ` | `mét trên giờ` | Generic word/unit |

## 10. Vietnamese Text Slash

| # | Input | Expected | Rule |
|---|-------|----------|------|
| 10.1 | `luận/Lời khuyên` | `luận hoặc Lời khuyên` | Vietnamese slash → "hoặc" |
| 10.2 | `đỏ/xanh` | `đỏ hoặc xanh` | Color slash |
| 10.3 | `Toán/Lý/Hóa` | `Toán hoặc Lý hoặc Hóa` | Multiple slashes (chained) |

## 11. Math Operators

### 11a. Multiplication `*`

| # | Input | Expected | Rule |
|---|-------|----------|------|
| 11.1 | `F = m * a` | `F bằng m nhân a` | Spaced multiplication |
| 11.2 | `2 * 3 = 6` | `2 nhân 3 bằng 6` | Number multiplication |
| 11.3 | `*Lục Vân Tiên*` | `Lục Vân Tiên` | Markdown italic — NOT "nhân" |
| 11.4 | `điểm **A**` | `điểm A` | Markdown bold — NOT "nhân" |

### 11b. Division `/`

| # | Input | Expected | Rule |
|---|-------|----------|------|
| 11.5 | `12 / 4 = 3` | `12 chia 4 bằng 3` | Number division |
| 11.6 | `a/b` | `a chia b` | Variable division (single letter) |

### 11c. Addition `+`

| # | Input | Expected | Rule |
|---|-------|----------|------|
| 11.7 | `2 + 3 = 5` | `2 cộng 3 bằng 5` | Addition |
| 11.8 | `NaOH + HCl` | `NaOH cộng HCl` | Chemical addition |

### 11d. Negative / Subtraction `-`

| # | Input | Expected | Rule |
|---|-------|----------|------|
| 11.9 | `-5` (start of string) | `âm 5` | Leading negative |
| 11.10 | `= -3` | `= âm 3` | Negative after equals |
| 11.11 | `+(-2)` | `cộng(âm 2)` | Negative in parens |
| 11.12 | `*(-3)` | `nhân(âm 3)` | Negative after multiply |

## 12. Number-Letter Separation

| # | Input | Expected | Rule |
|---|-------|----------|------|
| 12.1 | `2x + 3 = 7` | `2 x cộng 3 bằng 7` | Coefficient-variable |
| 12.2 | `3a` | `3 a` | Number-letter |
| 12.3 | `x2` | `x 2` | Letter-number (xN pattern) |

## 13. Equality & Zero

| # | Input | Expected | Rule |
|---|-------|----------|------|
| 13.1 | `x = 5` | `x bằng 5` | Equals → bằng |
| 13.2 | `a = b` | `a bằng b` | Variable equality |
| 13.3 | `x = 0` | `x bằng không` | Zero → không |
| 13.4 | `10` | `10` | Zero inside number stays |
| 13.5 | `0x` | `0 x` | Zero before letter |

## 14. Parentheses

| # | Input | Expected | Rule |
|---|-------|----------|------|
| 14.1 | `(2 + 3)` | `mở ngoặc 2 cộng 3 đóng ngoặc` | Math expression in parens |
| 14.2 | `(xin chào)` | `xin chào` | Non-math parens → removed |
| 14.3 | `Ca(OH)2` | `Ca(OH) hai` | Chemical parens preserved |

## 15. Interjections

| # | Input | Expected | Rule |
|---|-------|----------|------|
| 15.1 | `Wao!` | `Oa!` | wao → Oa |
| 15.2 | `Wow` | `Oa` | wow → Oa |
| 15.3 | `hahahaha` | `ha ha` | Repeated ha |
| 15.4 | `hihihi` | `hì hì` | hihi → hì hì |
| 15.5 | `hehe` | `hê hê` | hehe → hê hê |
| 15.6 | `huhuhu` | `hu hu` | huhu → hu hu |

## 16. Cleanup

| # | Input | Expected | Rule |
|---|-------|----------|------|
| 16.1 | `) xin chào` | `xin chào` | Leading `)` stripped |
| 16.2 | `xin chào ]` | `xin chào` | Trailing `]` stripped |
| 16.3 | `()` | `` (empty) | Empty parens removed |
| 16.4 | `[]` | `` (empty) | Empty brackets removed |
| 16.5 | `a . . . b` | `a . b` | Multiple periods collapsed |
| 16.6 | `xin chào .` | `xin chào.` | Space before period removed |

## 17. Combined / Real-World Scenarios

| # | Input | Expected | Rule |
|---|-------|----------|------|
| 17.1 | `Đừng đau đầu nữa, nghỉ ngơi một chút nhé!` | `Đừng đau đầu nữa, nghỉ ngơi một chút nhé!` | Natural Vietnamese — unchanged |
| 17.2 | `Tuy nhiên, tớ là Lisa — một trợ lý học tập AI` | `Tuy nhiên, tớ là Lisa . một trợ lý học tập AI` | Em dash in intro |
| 17.3 | `PTIT is well-known for its high-quality training` | `PTIT is well-known for its high-quality training` | English hyphens preserved |
| 17.4 | `"cutting-edge" tiên tiến` | `"cutting-edge" tiên tiến` | Quoted compound adjective |
| 17.5 | `Đoạn văn này theo phong cách: giới thiệu, nội dung, kết luận/Lời khuyên` | `Đoạn văn này theo phong cách: giới thiệu, nội dung, kết luận hoặc Lời khuyên` | Vietnamese slash in context |
| 17.6 | `Resilience: Sự kiên cường, khả năng phục hồi — đức tính quý giá` | `Resilience: Sự kiên cường, khả năng phục hồi . đức tính quý giá` | Em dash + Vietnamese |
| 17.7 | `*cutting-edge* tiên tiến` | `cutting-edge tiên tiến` | Markdown around hyphenated word |
| 17.8 | `x - 3 lớn hơn 0` | `x trừ 3 lớn hơn 0` | Math inequality |
| 17.9 | `tháng 8 - tháng 10` | `tháng 8 đến tháng 10` | Date range |
| 17.10 | `2H2 + O2 -> 2H2O` | `2 H hai cộng O hai cho ra 2 H hai O` | Full chemical equation |
| 17.11 | `NaOH + HCl -> NaCl + H2O` | `NaOH cộng HCl cho ra NaCl cộng H hai O` | Acid-base reaction |
| 17.12 | `F = m * a` | `F bằng m nhân a` | Newton's second law |
| 17.13 | `v = s/t` | `v bằng s chia t` | Speed formula |
| 17.14 | `$3x - 2 = 0$` | `3 x trừ 2 bằng 0` | Equation in LaTeX |
| 17.15 | `Học viện Công nghệ Bưu chính Viễn thông (PTIT)` | `Học viện Công nghệ Bưu chính Viễn thông PTIT` | Parentheses around abbreviation |

## 18. Edge Cases

| # | Input | Expected | Rule |
|---|-------|----------|------|
| 18.1 | `` | `` | Empty string |
| 18.2 | `   ` | `` | Whitespace only |
| 18.3 | `😀🎉` | `` | Emoji only → empty |
| 18.4 | `---` | `. ` | Dashes only |
| 18.5 | `*` | `` | Single asterisk |
| 18.6 | `**` | `` | Double asterisk |
| 18.7 | `$` | `` | Dollar sign only |
| 18.8 | `0` | `0` | Single zero (digit, not standalone) |
| 18.9 | `a` | `a` | Single letter |
| 18.10 | `PTIT là ngôi trường tốt` | `PTIT là ngôi trường tốt` | Vietnamese text unchanged |
| 18.11 | `Đại học Bách Khoa Hà Nội` | `Đại học Bách Khoa Hà Nội` | University name unchanged |
| 18.12 | `Tớ rất vui được gặp cậu!` | `Tớ rất vui được gặp cậu!` | Friendly Vietnamese — unchanged |

---

## Known Limitations

| Case | Current Output | Issue |
|------|---------------|-------|
| `2H2 + O2` | `2 H hai cộng O hai` | `H2` after coefficient digit `2` not matched by chem regex (lookbehind blocks) |
| `150-200` | `150-200` | No-space number range stays as-is (by design — could be a model number) |
| `x2` | `x 2` | Letter-digit split may break variable names like `x2` in programming context |
| `NaOH` | `NaOH` | Formula without digits read character-by-character by TTS |

## Regression Tests (Bugs That Were Fixed)

| # | Input | Old Bug | Fixed Output |
|---|-------|---------|-------------|
| R.1 | `*Lục Vân Tiên*` | `nhân Lục Vân Tiên nhân` | `Lục Vân Tiên` |
| R.2 | `cutting-edge` | `cutting trừ edge` | `cutting-edge` |
| R.3 | `well-known` | `well trừ known` | `well-known` |
| R.4 | `CaCO3` | `CaCO3` (unchanged) | `Ca C O ba` |
| R.5 | `H2O` | `H2 O` (split wrong) | `H hai O` |
| R.6 | `—` em dash | `—` (passed through) | ` . ` |
| R.7 | `luận/Lời` | `luận chia Lời` | `luận hoặc Lời` |
| R.8 | `tháng 8 - tháng 10` | `tháng 8 trừ tháng 10` | `tháng 8 đến tháng 10` |
