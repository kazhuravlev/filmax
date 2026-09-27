package com.filmax.detekt.rules

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider

class FilmaxRuleSetProvider : RuleSetProvider {
    override val ruleSetId = "filmax-rules"

    override fun instance(config: Config): RuleSet =
        RuleSet(ruleSetId, listOf(NestedIf(config)))
}
