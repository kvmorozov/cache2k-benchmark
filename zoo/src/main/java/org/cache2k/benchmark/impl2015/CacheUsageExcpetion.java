package org.cache2k.benchmark.impl2015;

/*
 * #%L
 * zoo
 * %%
 * Copyright (C) 2013 - 2016 headissue GmbH, Munich
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

/**
 * Indicates cache missusage including configuration error.
 *
 * @author Jens Wilke; created: 2013-12-17
 */
public class CacheUsageExcpetion extends RuntimeException {

  public CacheUsageExcpetion() {
  }

  public CacheUsageExcpetion(String message) {
    super(message);
  }

  public CacheUsageExcpetion(String message, Throwable cause) {
    super(message, cause);
  }

  public CacheUsageExcpetion(Throwable cause) {
    super(cause);
  }

}
