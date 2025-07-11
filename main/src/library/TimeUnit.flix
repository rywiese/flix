/*
 *  Copyright 2024 Magnus Madsen
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

///
/// Represents a time unit.
///
enum TimeUnit with Eq {
    case Days,
    case Hours,
    case Microseconds,
    case Milliseconds,
    case Minutes,
    case Nanoseconds,
    case Seconds
}

mod TimeUnit {

    ///
    /// Returns the number of miliseconds for timeunit `u`
    ///
    pub def toMilliseconds(u: TimeUnit): Int32 = match u {
           case TimeUnit.Days         => 24 * 60 * 60 * 1_000,
           case TimeUnit.Hours        => 60 * 60 * 1_000,
           case TimeUnit.Microseconds => 1 / 1_000,
           case TimeUnit.Milliseconds => 1,
           case TimeUnit.Minutes      => 60 * 1_000,
           case TimeUnit.Nanoseconds  => 1 / 1_000_000,
           case TimeUnit.Seconds      => 1_000
        }

}
