/*
 * Copyright 2023 Magnus Madsen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
use IoError.ErrorKind
use IoError.IoError

import java.io.InputStream
import java.io.IOException

///
/// A trait for types which are resources that can be read from.
///
trait Readable[t] {

    ///
    /// Type associated with the data read from the underlying resource.
    ///
    type Aty

    ///
    /// Effect associated with reading data from the underlying resource.
    ///
    type Aef: Eff

    ///
    /// Reads `k` items from the underlying resource and writes them into `b`.
    ///
    /// Returns `Ok(k)` to signify that `k` items were successfully read and written to `b`.
    ///
    /// Guarantees that `0 <= k <= length(b)`.
    ///
    /// Returns `Err(e)` if some underlying I/O error occurs.
    ///
    pub def read(b: Array[Readable.Aty[t], r], r: t): Result[IoError, Int32] \ r + Readable.Aef[t]

}

instance Readable[InputStream] {

    type Aty = Int8

    type Aef = IO

    pub def read(b: Array[Int8, r], r: InputStream): Result[IoError, Int32] \ r + IO =
        try {
            let result = r.read(b);
            let numRead = if (result == -1) 0 else result;
            checked_ecast(Ok(numRead))
        } catch {
            case ex: IOException => Err(IoError(ErrorKind.Other, ex.getMessage()))
        }

}
