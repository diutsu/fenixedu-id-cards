/**
 * Copyright © 2014 Instituto Superior Técnico
 *
 * This file is part of FenixEdu Identification Cards.
 *
 * FenixEdu Identification Cards is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * FenixEdu Identification Cards is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with FenixEdu Identification Cards.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.fenixedu.idcards.ui;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.struts.action.ActionForm;
import org.apache.struts.action.ActionForward;
import org.apache.struts.action.ActionMapping;
import org.fenixedu.academic.domain.ExecutionYear;
import org.fenixedu.academic.domain.Person;
import org.fenixedu.academic.ui.struts.action.base.FenixDispatchAction;
import org.fenixedu.bennu.struts.annotations.Forward;
import org.fenixedu.bennu.struts.annotations.Forwards;
import org.fenixedu.bennu.struts.annotations.Mapping;
import org.fenixedu.bennu.struts.portal.EntryPoint;
import org.fenixedu.bennu.struts.portal.StrutsFunctionality;
import org.fenixedu.idcards.domain.SantanderBatch;
import org.fenixedu.idcards.domain.SantanderBatchSender;
import org.fenixedu.idcards.domain.SantanderCardInformation;
import org.fenixedu.idcards.domain.SantanderSequenceNumberGenerator;
import org.joda.time.DateTime;

import pt.ist.fenixframework.Atomic;
import pt.ist.fenixframework.FenixFramework;

import com.google.common.base.Charsets;
import com.google.common.io.CharStreams;

@StrutsFunctionality(app = IdCardsApp.class, path = "santander-cards", titleKey = "subtitle.santander.cards")
@Mapping(module = "identificationCardManager", path = "/manageSantander")
@Forwards({ @Forward(name = "entryPoint", path = "/identificationCardManager/santander/showSantanderBatches.jsp"),
        @Forward(name = "uploadCardInfo", path = "/identificationCardManager/cardGeneration/uploadCardInfo.jsp") })
public class ManageSantanderCardGenerationDA extends FenixDispatchAction {

    private static final int ERROR_LINE_SIZE = -1;
    private static final int ERROR_NUMBER_ENTRIES = -2;
    private static final int ERROR_FILE_ALREADY_SUBMITED = -3;
    private static final int ERROR_USERNAME_DOESNT_EXIST = -4;
    private static final int NEW_FILE_SUBMITED = 0;

    @EntryPoint
    public ActionForward intro(final ActionMapping mapping, final ActionForm actionForm, final HttpServletRequest request,
            final HttpServletResponse response) throws Exception {
        request.setAttribute("santanderBean", new ManageSantanderCardGenerationBean());
        request.setAttribute("uploadDCHPFileBean", new OpenFileBean());
        return mapping.findForward("entryPoint");
    }

    public ActionForward selectExecutionYearPostback(final ActionMapping mapping, final ActionForm actionForm,
            final HttpServletRequest request, final HttpServletResponse response) throws Exception {
        ManageSantanderCardGenerationBean santanderBean = getRenderedObject("santanderBean");
        ExecutionYear year = santanderBean.getExecutionYear();
        if (year != null) {
            refreshBeanState(santanderBean);
        }
        request.setAttribute("santanderBean", santanderBean);
        request.setAttribute("uploadDCHPFileBean", new OpenFileBean());
        return mapping.findForward("entryPoint");
    }

    public ActionForward submitDCHPFile(final ActionMapping mapping, final ActionForm actionForm,
            final HttpServletRequest request, final HttpServletResponse response) throws Exception {
        OpenFileBean dchpFileBean = getRenderedObject("uploadDCHPFileBean");

        try {
            final String stringResults = readFile(dchpFileBean);
            String[] splitedFile = stringResults.split("\r\n");
            int error = isFileFormatCorrected(splitedFile);
            if (error == ERROR_LINE_SIZE) {
                addErrorMessage(request, "errors", "message.dchp.file.submit.wrong.line.size");
            } else if (error == ERROR_NUMBER_ENTRIES) {
                addErrorMessage(request, "errors", "message.dchp.file.submit.wrong.number.entries");
            } else if (error == ERROR_FILE_ALREADY_SUBMITED) {
                addErrorMessage(request, "errors", "message.dchp.file.submit.already.submited");
            } else if (error == ERROR_USERNAME_DOESNT_EXIST) {
                addErrorMessage(request, "errors", "message.dchp.file.submit.wrong.username", SantanderCardInformation
                        .getIdentificationCardNumber(splitedFile[1]).trim(), 2);
            } else {
                /*store the new entries of the dchp file*/
                boolean success = true;
                for (int i = 1; i < splitedFile.length - 1; i++) {
                    String detailedLine = splitedFile[i];
                    /*get Person object*/
                    String username = SantanderCardInformation.getIdentificationCardNumber(detailedLine).trim();
                    Person p = Person.findByUsername(username);
                    if (p == null) {
                        addErrorMessage(request, "errors", "message.dchp.file.submit.wrong.username", username, i + 1);
                        success = false;
                        continue;
                    }
                    /*create new CardInformation*/
                    createNewCardInformation(p, detailedLine);
                }
                if (success) {
                    request.setAttribute("success", "true");
                } else {
                    request.setAttribute("someSuccess", "true");
                }
            }
        } catch (NumberFormatException e) {
            addErrorMessage(request, "errors", "message.dchp.file.submit.wrong.format");
        } catch (IOException e) {
            addErrorMessage(request, e.getMessage(), e.getMessage());
        }
        request.setAttribute("santanderBean", new ManageSantanderCardGenerationBean());
        request.setAttribute("uploadDCHPFileBean", new OpenFileBean());
        return mapping.findForward("entryPoint");
    }

    private String readFile(OpenFileBean dchpFileBean) throws IOException {
        return CharStreams.toString(new InputStreamReader(dchpFileBean.getInputStream(), Charsets.UTF_8));
    }

    public ActionForward createBatch(final ActionMapping mapping, final ActionForm actionForm, final HttpServletRequest request,
            final HttpServletResponse response) throws Exception {
        ExecutionYear executionYear = FenixFramework.getDomainObject(request.getParameter("executionYearEid"));
        ManageSantanderCardGenerationBean santanderBean;

        if (executionYear == null) {
            addErrorMessage(request, "errors", "error.cantCreateNewBatchForExecutionYearNull");
            santanderBean = new ManageSantanderCardGenerationBean();
        } else {
            santanderBean = new ManageSantanderCardGenerationBean(executionYear);
            Person requester = getUserView(request).getPerson();
            createNewBatch(requester, santanderBean.getExecutionYear());
            refreshBeanState(santanderBean);
        }

        request.setAttribute("santanderBean", santanderBean);
        request.setAttribute("uploadDCHPFileBean", new OpenFileBean());
        return mapping.findForward("entryPoint");
    }

    /*
     * Download | Send | Delete
     */

    public ActionForward downloadBatch(final ActionMapping mapping, final ActionForm actionForm,
            final HttpServletRequest request, final HttpServletResponse response) throws Exception {
        SantanderBatch santanderBatch = FenixFramework.getDomainObject(request.getParameter("santanderBatchEid"));
        ExecutionYear executionYear = FenixFramework.getDomainObject(request.getParameter("executionYearEid"));
        ManageSantanderCardGenerationBean santanderBean;

        try {
            String fileString = santanderBatch.generateTUI();
            response.setContentType("text/plain");
            response.setHeader("Content-disposition",
                    "attachment; filename=SantanderTecnico_TUI_" + (new DateTime()).toString("yyyyMMddHHmm") + ".txt");
            final ServletOutputStream writer = response.getOutputStream();
            writer.write(fileString.getBytes("Cp1252"));
            writer.flush();
            response.flushBuffer();
        } catch (Exception e) {
            addErrorMessage(request, "errors", "error.generatingTUIFailed " + e.getMessage());
            santanderBean = new ManageSantanderCardGenerationBean(executionYear);
            refreshBeanState(santanderBean);
            request.setAttribute("santanderBean", santanderBean);
            request.setAttribute("uploadDCHPFileBean", new OpenFileBean());
            return mapping.findForward("entryPoint");
        }

        return null;
    }

    public ActionForward downloadDDXR(final ActionMapping mapping, final ActionForm actionForm, final HttpServletRequest request,
            final HttpServletResponse response) throws Exception {
        SantanderBatch santanderBatch = FenixFramework.getDomainObject(request.getParameter("santanderBatchEid"));
        ExecutionYear executionYear = FenixFramework.getDomainObject(request.getParameter("executionYearEid"));
        ManageSantanderCardGenerationBean santanderBean;

        try {
            response.setContentType("application/zip");
            response.setHeader("Content-disposition",
                    "attachment; filename=SantanderTecnico_DDXR&Photos_" + (new DateTime()).toString("yyyyMMddHHmm") + ".zip");
            final ServletOutputStream writer = response.getOutputStream();
            final byte[] zipFile = santanderBatch.getPhotosAndDDXR();
            writer.write(zipFile);
            writer.flush();
            response.flushBuffer();
        } catch (Exception e) {
            addErrorMessage(request, "errors", "error.generatingDDXR&PhotosFailed " + e.getMessage());
            santanderBean = new ManageSantanderCardGenerationBean(executionYear);
            refreshBeanState(santanderBean);
            request.setAttribute("santanderBean", santanderBean);
            request.setAttribute("uploadDCHPFileBean", new OpenFileBean());
            return mapping.findForward("entryPoint");
        }

        return null;
    }

    public ActionForward sendBatch(final ActionMapping mapping, final ActionForm actionForm, final HttpServletRequest request,
            final HttpServletResponse response) throws Exception {
        SantanderBatch santanderBatch = FenixFramework.getDomainObject(request.getParameter("santanderBatchEid"));
        Person requester = getUserView(request).getPerson();
        sealBatch(santanderBatch, requester);
        return downloadBatch(mapping, actionForm, request, response);
    }

    public ActionForward deleteBatch(final ActionMapping mapping, final ActionForm actionForm, final HttpServletRequest request,
            final HttpServletResponse response) throws Exception {
        SantanderBatch santanderBatch = FenixFramework.getDomainObject(request.getParameter("santanderBatchEid"));
        ExecutionYear executionYear = FenixFramework.getDomainObject(request.getParameter("executionYearEid"));
        ManageSantanderCardGenerationBean santanderBean;

        destroyBatch(santanderBatch);

        if (executionYear == null) {
            addErrorMessage(request, "errors", "error.lostTrackOfExecutionYear");
            santanderBean = new ManageSantanderCardGenerationBean();
        } else {
            santanderBean = new ManageSantanderCardGenerationBean(executionYear);
            refreshBeanState(santanderBean);
        }

        request.setAttribute("santanderBean", santanderBean);
        request.setAttribute("uploadDCHPFileBean", new OpenFileBean());
        return mapping.findForward("entryPoint");
    }

    private List<SantanderBatch> retrieveBatches(ExecutionYear year) {
        List<SantanderBatch> batches = new ArrayList<SantanderBatch>(year.getSantanderBatchesSet());
        Collections.sort(batches, SantanderBatch.COMPARATOR_BY_MOST_RECENTLY_CREATED);
        return batches;
    }

    private boolean canCreateNewBatch(ExecutionYear year) {
        List<SantanderBatch> batches = retrieveBatches(year);
        if (batches.isEmpty()) {
            return true;
        }
        SantanderBatch lastCreatedBatch = batches.iterator().next();
        return (lastCreatedBatch != null && lastCreatedBatch.getSent() != null);
    }

    private int isFileFormatCorrected(String[] splitedFile) {
        //FIXME - santander specification says every line should be sized 730, but all lines are 731
        //       Hence, this verification is commented
//        int len = splitedFile.length - 1;
//        for (int i = 0; i < len; ++i) {
//            if (splitedFile[i].length() != 730) {
//                return ERROR_LINE_SIZE;
//            }
//        }

        String firstDetailedLine = (splitedFile.length > 1) ? splitedFile[1] : null;
        int numberOfRegisters = (splitedFile.length > 1) ? Integer.parseInt(splitedFile[0].substring(32, 41)) : 0;
        /*verify the number of registers*/
        if (firstDetailedLine == null || (splitedFile.length - 2) != numberOfRegisters) {
            return ERROR_NUMBER_ENTRIES;
        }
        String ist_id = SantanderCardInformation.getIdentificationCardNumber(firstDetailedLine).trim();
        Person person = Person.findByUsername(ist_id);
        if (person == null) {
            return ERROR_USERNAME_DOESNT_EXIST;
        }
        Set<SantanderCardInformation> cards_info = person.getSantanderCardsInformationSet();
        for (SantanderCardInformation card_info : cards_info) {
            if (card_info.getDchpRegisteLine().equals(firstDetailedLine)) {
                return ERROR_FILE_ALREADY_SUBMITED;
            }
        }
        return NEW_FILE_SUBMITED;
    }

    @Atomic
    private void createNewBatch(Person requester, ExecutionYear executionYear) {
        new SantanderBatch(requester, executionYear);
    }

    @Atomic
    private void destroyBatch(SantanderBatch batch) {
        batch.delete();
    }

    @Atomic
    private SantanderCardInformation createNewCardInformation(Person person, String line) {
        return new SantanderCardInformation(person, line);
    }

    @Atomic
    private void deleteCardInformation(SantanderCardInformation card_info) {
        card_info.delete();
    }

    private void refreshBeanState(ManageSantanderCardGenerationBean santanderBean) {
        santanderBean.setSantanderBatches(retrieveBatches(santanderBean.getExecutionYear()));
        santanderBean.setAllowNewCreation(canCreateNewBatch(santanderBean.getExecutionYear()));
    }

    @Atomic
    private void sealBatch(SantanderBatch santanderBatch, Person requester) {
        santanderBatch.setSequenceNumber(SantanderSequenceNumberGenerator.getNewSequenceNumber());
        santanderBatch.setSent(new DateTime());
        santanderBatch.setSantanderBatchSender(new SantanderBatchSender(requester));
    }

}
