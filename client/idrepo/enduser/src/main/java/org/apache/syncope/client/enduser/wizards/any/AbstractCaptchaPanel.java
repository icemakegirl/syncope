/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.syncope.client.enduser.wizards.any;

import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.ajax.markup.html.form.AjaxButton;
import org.apache.wicket.extensions.markup.html.captcha.CaptchaImageResource;
import org.apache.wicket.markup.ComponentTag;
import org.apache.wicket.markup.html.form.RequiredTextField;
import org.apache.wicket.markup.html.image.Image;
import org.apache.wicket.markup.html.panel.Panel;
import org.apache.wicket.model.PropertyModel;

public abstract class AbstractCaptchaPanel<T> extends Panel {

    private static final long serialVersionUID = -4310189409064713307L;

    protected String randomText;

    protected String captchaText;

    protected final CaptchaImageResource captchaImageResource;

    public AbstractCaptchaPanel(final String id) {
        super(id);
        this.setOutputMarkupId(true);

        captchaImageResource = createCaptchaImageResource();
        final Image captchaImage = new Image("image", captchaImageResource);
        captchaImage.setOutputMarkupId(true);
        add(captchaImage);

        AjaxButton realoadCaptchaButton = new AjaxButton("reloadButton") {

            private static final long serialVersionUID = -957948639666058749L;

            @Override
            protected void onSubmit(final AjaxRequestTarget target) {
                captchaImageResource.invalidate();
                target.add(captchaImage);
            }

        };
        realoadCaptchaButton.setDefaultFormProcessing(false);
        add(realoadCaptchaButton);

        add(new RequiredTextField<String>("captcha",
                new PropertyModel<>(AbstractCaptchaPanel.this, "captchaText"), String.class) {

            private static final long serialVersionUID = -5918382907429502528L;

            @Override
            protected void onComponentTag(final ComponentTag tag) {
                super.onComponentTag(tag);
                // clear the field after each render
                tag.put("value", "");
            }

        });
    }

    public String getRandomText() {
        return randomText;
    }

    public String getCaptchaText() {
        return captchaText;
    }

    protected abstract CaptchaImageResource createCaptchaImageResource();

    protected abstract void reload();
}
