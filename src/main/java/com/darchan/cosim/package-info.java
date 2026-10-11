/**
 * Co-simulation glue: the only production package allowed to depend on both the gate-level layer
 * ({@code com.darchan.physical}) and the behavioral layer ({@code com.darchan.behavioral}). Nothing in physical,
 * behavioral or diag may depend on it (enforced by {@code ArchitectureTest}).
 */
package com.darchan.cosim;
