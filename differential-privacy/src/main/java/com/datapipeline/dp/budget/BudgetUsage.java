package com.datapipeline.dp.budget;

import java.time.Instant;

public record BudgetUsage(double epsilon, double delta, Instant timestamp) {}
