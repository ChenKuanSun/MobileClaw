package ai.affiora.mobileclaw.skills

import android.content.Context
import android.content.res.AssetManager
import ai.affiora.mobileclaw.data.prefs.UserPreferences
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class SkillsManagerTest {

    private lateinit var context: Context
    private lateinit var assetManager: AssetManager
    private lateinit var userPreferences: UserPreferences
    private lateinit var skillsManager: SkillsManager

    @BeforeEach
    fun setUp() {
        context = mockk(relaxed = true)
        assetManager = mockk(relaxed = true)
        userPreferences = mockk(relaxed = true)

        every { context.assets } returns assetManager
        every { userPreferences.activeSkillIds } returns flowOf(emptySet())

        skillsManager = SkillsManager(context, userPreferences)
    }

    @Test
    fun `parseSkillMd extracts all frontmatter fields`() {
        val markdown = """
            |---
            |name: phone-basics
            |description: Basic phone operations - SMS, calls, contacts
            |version: "1.0"
            |author: MobileClaw Built-in
            |tools_required: sms, callLog, contacts
            |---
            |# Phone Basics
            |## Role
            |You help the user with basic phone tasks.
        """.trimMargin()

        val skill = skillsManager.parseSkillMd("phone-basics", markdown)

        assertThat(skill).isNotNull()
        assertThat(skill!!.id).isEqualTo("phone-basics")
        assertThat(skill.name).isEqualTo("phone-basics")
        assertThat(skill.description).isEqualTo("Basic phone operations - SMS, calls, contacts")
        assertThat(skill.version).isEqualTo("1.0")
        assertThat(skill.author).isEqualTo("MobileClaw Built-in")
        assertThat(skill.toolsRequired).containsExactly("sms", "callLog", "contacts")
        assertThat(skill.content).contains("# Phone Basics")
        assertThat(skill.content).contains("You help the user with basic phone tasks.")
    }

    @Test
    fun `parseSkillMd handles YAML list format for tools_required`() {
        val markdown = """
            |---
            |name: test-skill
            |description: Test
            |version: "2.0"
            |author: Test Author
            |tools_required: [sms, calendar, contacts]
            |---
            |# Test
        """.trimMargin()

        val skill = skillsManager.parseSkillMd("test-skill", markdown)

        assertThat(skill).isNotNull()
        assertThat(skill!!.toolsRequired).containsExactly("sms", "calendar", "contacts")
    }

    @Test
    fun `parseSkillMd returns null for invalid markdown without frontmatter`() {
        val markdown = "# No frontmatter here"

        val skill = skillsManager.parseSkillMd("bad", markdown)

        assertThat(skill).isNull()
    }

    @Test
    fun `parseSkillMd returns null when closing delimiter is missing`() {
        val markdown = """
            |---
            |name: broken
            |description: No closing delimiters
        """.trimMargin()

        val skill = skillsManager.parseSkillMd("broken", markdown)

        assertThat(skill).isNull()
    }

    @Test
    fun `parseSkillMd returns null when name field is missing`() {
        val markdown = """
            |---
            |description: No name field
            |version: "1.0"
            |author: Test
            |tools_required: sms
            |---
            |# Content
        """.trimMargin()

        val skill = skillsManager.parseSkillMd("no-name", markdown)

        assertThat(skill).isNull()
    }

    @Test
    fun `parseSkillMd handles quoted version values`() {
        val markdown = """
            |---
            |name: quoted
            |description: Quoted version test
            |version: '2.5'
            |author: Test
            |tools_required: sms
            |---
            |# Content
        """.trimMargin()

        val skill = skillsManager.parseSkillMd("quoted", markdown)

        assertThat(skill).isNotNull()
        assertThat(skill!!.version).isEqualTo("2.5")
    }

    @Test
    fun `parseSkillMd defaults version to 1_0 when missing`() {
        val markdown = """
            |---
            |name: no-version
            |description: Missing version
            |author: Test
            |tools_required: sms
            |---
            |# Content
        """.trimMargin()

        val skill = skillsManager.parseSkillMd("no-version", markdown)

        assertThat(skill).isNotNull()
        assertThat(skill!!.version).isEqualTo("1.0")
    }

    @Test
    fun `loadSkill reads from assets and parses correctly`() {
        val markdown = """
            |---
            |name: phone-basics
            |description: Basic phone ops
            |version: "1.0"
            |author: Built-in
            |tools_required: sms
            |---
            |# Phone Basics
        """.trimMargin()

        every {
            assetManager.open("skills/built-in/phone-basics/SKILL.md")
        } returns ByteArrayInputStream(markdown.toByteArray())

        val skill = skillsManager.loadSkill("phone-basics")

        assertThat(skill).isNotNull()
        assertThat(skill!!.id).isEqualTo("phone-basics")
        assertThat(skill.name).isEqualTo("phone-basics")
    }

    @Test
    fun `loadSkill returns null when asset does not exist`() {
        every {
            assetManager.open(any())
        } throws java.io.FileNotFoundException("not found")

        val skill = skillsManager.loadSkill("nonexistent")

        assertThat(skill).isNull()
    }

    @Test
    fun `getAllSkills scans all categories`() {
        every { assetManager.list("skills/built-in") } returns arrayOf("phone-basics")
        every { assetManager.list("skills/vertical") } returns arrayOf("real-estate")
        every { assetManager.list("skills/user") } returns arrayOf<String>()

        val phoneMarkdown = """
            |---
            |name: phone-basics
            |description: Phone ops
            |version: "1.0"
            |author: Built-in
            |tools_required: sms
            |---
            |# Phone
        """.trimMargin()

        val realEstateMarkdown = """
            |---
            |name: real-estate
            |description: Real estate
            |version: "1.0"
            |author: Built-in
            |tools_required: sms, calendar
            |---
            |# Real Estate
        """.trimMargin()

        every {
            assetManager.open("skills/built-in/phone-basics/SKILL.md")
        } returns ByteArrayInputStream(phoneMarkdown.toByteArray())

        every {
            assetManager.open("skills/vertical/real-estate/SKILL.md")
        } returns ByteArrayInputStream(realEstateMarkdown.toByteArray())

        val skills = skillsManager.getAllSkills()

        assertThat(skills).hasSize(2)
        assertThat(skills.map { it.id }).containsExactly("phone-basics", "real-estate")
    }

    @Test
    fun `getActiveSkills returns only enabled skills`() = runTest {
        every { userPreferences.activeSkillIds } returns flowOf(setOf("phone-basics"))

        every { assetManager.list("skills/built-in") } returns arrayOf("phone-basics", "morning-routine")
        every { assetManager.list("skills/vertical") } returns arrayOf<String>()
        every { assetManager.list("skills/user") } returns arrayOf<String>()

        val phoneMarkdown = """
            |---
            |name: phone-basics
            |description: Phone ops
            |version: "1.0"
            |author: Built-in
            |tools_required: sms
            |---
            |# Phone
        """.trimMargin()

        val morningMarkdown = """
            |---
            |name: morning-routine
            |description: Morning briefing
            |version: "1.0"
            |author: Built-in
            |tools_required: callLog
            |---
            |# Morning
        """.trimMargin()

        every {
            assetManager.open("skills/built-in/phone-basics/SKILL.md")
        } returns ByteArrayInputStream(phoneMarkdown.toByteArray())

        every {
            assetManager.open("skills/built-in/morning-routine/SKILL.md")
        } returns ByteArrayInputStream(morningMarkdown.toByteArray())

        val active = skillsManager.getActiveSkills()

        assertThat(active).hasSize(1)
        assertThat(active[0].id).isEqualTo("phone-basics")
    }

    @Test
    fun `enableSkill adds skill id to preferences`() = runTest {
        every { userPreferences.activeSkillIds } returns flowOf(setOf("existing-skill"))
        val capturedIds = slot<Set<String>>()
        coEvery { userPreferences.setActiveSkillIds(capture(capturedIds)) } returns Unit

        skillsManager.enableSkill("new-skill")

        coVerify { userPreferences.setActiveSkillIds(any()) }
        assertThat(capturedIds.captured).containsExactly("existing-skill", "new-skill")
    }

    @Test
    fun `disableSkill removes skill id from preferences`() = runTest {
        every { userPreferences.activeSkillIds } returns flowOf(setOf("skill-a", "skill-b"))
        val capturedIds = slot<Set<String>>()
        coEvery { userPreferences.setActiveSkillIds(capture(capturedIds)) } returns Unit

        skillsManager.disableSkill("skill-a")

        coVerify { userPreferences.setActiveSkillIds(any()) }
        assertThat(capturedIds.captured).containsExactly("skill-b")
    }

    @Test
    fun `disableSkill is no-op when skill not in active set`() = runTest {
        every { userPreferences.activeSkillIds } returns flowOf(setOf("skill-a"))
        val capturedIds = slot<Set<String>>()
        coEvery { userPreferences.setActiveSkillIds(capture(capturedIds)) } returns Unit

        skillsManager.disableSkill("nonexistent")

        coVerify { userPreferences.setActiveSkillIds(any()) }
        assertThat(capturedIds.captured).containsExactly("skill-a")
    }

    @Test
    fun `parseSkillMd handles empty tools_required`() {
        val markdown = """
            |---
            |name: minimal
            |description: Minimal skill
            |version: "1.0"
            |author: Test
            |tools_required:
            |---
            |# Minimal
        """.trimMargin()

        val skill = skillsManager.parseSkillMd("minimal", markdown)

        assertThat(skill).isNotNull()
        assertThat(skill!!.toolsRequired).isEmpty()
    }

    @Test
    fun `loadSkill checks all categories in order`() {
        // First two categories throw, third succeeds
        every {
            assetManager.open("skills/built-in/my-skill/SKILL.md")
        } throws java.io.FileNotFoundException("not found")

        val markdown = """
            |---
            |name: my-skill
            |description: Found in vertical
            |version: "1.0"
            |author: Test
            |tools_required: sms
            |---
            |# My Skill
        """.trimMargin()

        every {
            assetManager.open("skills/vertical/my-skill/SKILL.md")
        } returns ByteArrayInputStream(markdown.toByteArray())

        val skill = skillsManager.loadSkill("my-skill")

        assertThat(skill).isNotNull()
        assertThat(skill!!.name).isEqualTo("my-skill")
    }
}
