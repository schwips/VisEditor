/*******************************************************************************
 * Copyright 2014 Pawel Pastuszak
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package pl.kotcrab.vis.ui;

public abstract class FormInputValidator implements InputValidator {
	private String errorMsg;
	private boolean result;

	public FormInputValidator (String errorMsg) {
		this.errorMsg = errorMsg;
	}

	public String getErrorMsg () {
		return errorMsg;
	}

	protected void setResult (boolean result) {
		this.result = result;
	}

	protected boolean getResult () {
		return result;
	}

	@Override
	public boolean validateInput (String input) {
		return result;
	}
}