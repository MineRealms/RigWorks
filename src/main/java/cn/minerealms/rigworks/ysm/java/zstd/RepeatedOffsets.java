/*
 * Originally derived from:
 * Source: https://github.com/YesSteveModel/YesSteveModel (Apache-2.0)
 *        and https://github.com/stevewongv/YesSteveModel (Apache-2.0)
 *
 * Licensed under the Apache License, Version 2.0.
 * You may obtain a copy of the License at:
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Modified by MineRealms for RigWorks.
 * Changes: Cleanroom reimplementation / package adaptation / API changes for in-mod YSM decode.
 */
/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.minerealms.rigworks.ysm.java.zstd;

class RepeatedOffsets
{
    private int offset0 = 1;
    private int offset1 = 4;

    private int tempOffset0;
    private int tempOffset1;

    public int getOffset0()
    {
        return offset0;
    }

    public int getOffset1()
    {
        return offset1;
    }

    public void saveOffset0(int offset)
    {
        tempOffset0 = offset;
    }

    public void saveOffset1(int offset)
    {
        tempOffset1 = offset;
    }

    public void commit()
    {
        offset0 = tempOffset0;
        offset1 = tempOffset1;
    }
}

