/*
 * Originally derived from:
 * Source: https://github.com/YesSteveModel/YesSteveModel (Apache-2.0)
 * Licensed under the Apache License, Version 2.0.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 * Modified by MineRealms for RigWorks.
 * Changes: preserve animation sound event names and timing.
 */
package cn.minerealms.rigworks.ysm.model;

public record YsmSoundKeyframe(String sound, float time) {
}
